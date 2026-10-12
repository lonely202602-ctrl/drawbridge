package com.drawbridge.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.adapter.ProtocolFamily;
import com.drawbridge.common.ApiResponse;
import com.drawbridge.common.BizException;
import com.drawbridge.common.ErrorCode;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.UpstreamModel;
import com.drawbridge.domain.mapper.ChannelMapper;
import com.drawbridge.domain.mapper.UpstreamModelMapper;
import com.drawbridge.health.AccountRegistry;
import com.drawbridge.health.ChannelHealthTracker;
import com.drawbridge.health.ChannelOpsService;
import com.drawbridge.health.CooldownRegistry;
import com.drawbridge.infrastructure.AesGcmCipher;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.http.UpstreamHttp;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理端-渠道：接入上游（协议模板 + Key + 路径覆盖）、试跑、模型同步/人工录入、启停。
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/channels")
public class ChannelAdminController {

    private final ChannelMapper channelMapper;
    private final UpstreamModelMapper upstreamModelMapper;
    private final ChannelConfigService configService;
    private final AesGcmCipher cipher;
    private final ChannelOpsService channelOps;
    private final ChannelHealthTracker healthTracker;
    private final CooldownRegistry cooldownRegistry;
    private final UpstreamHttp http;
    private final AccountRegistry accountRegistry;

    public record ChannelUpsertRequest(
            @NotBlank String name,
            @NotBlank String protocol,
            @NotBlank String baseUrl,
            String apiKey,
            Map<String, String> pathOverrides,
            Map<String, Object> extraConfig,
            Integer maxConcurrency,
            String remark) {
    }

    /** 扁平结构返回（前端直接读顶层字段），密钥永不外传。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> rows = channelMapper.selectList(
                        Wrappers.<Channel>lambdaQuery().orderByDesc(Channel::getId))
                .stream()
                .map(c -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", c.getId());
                    m.put("name", c.getName());
                    m.put("protocol", c.getProtocol());
                    m.put("baseUrl", c.getBaseUrl());
                    m.put("pathOverrides", c.getPathOverrides());
                    m.put("extraConfig", c.getExtraConfig());
                    m.put("maxConcurrency", c.getMaxConcurrency());
                    m.put("accountKey", c.getAccountKey() == null ? "" : AccountRegistry.shortKey(c.getAccountKey()));
                    m.put("accountCapacity", c.getAccountKey() == null ? null : accountRegistry.capacityOf(c.getAccountKey()));
                    m.put("enabled", c.getEnabled());
                    m.put("status", c.getStatus());
                    m.put("statusReason", c.getStatusReason() == null ? "" : c.getStatusReason());
                    m.put("remark", c.getRemark());
                    m.put("keyConfigured", c.getApiKeyEnc() != null && !c.getApiKeyEnc().isBlank());
                    m.put("health", healthTracker.healthState(c.getId()));
                    m.put("cooling", cooldownRegistry.isCooling(c.getId()));
                    return m;
                })
                .toList();
        return ApiResponse.ok(rows);
    }

    @PostMapping
    public ApiResponse<Long> create(@Validated @RequestBody ChannelUpsertRequest body) {
        validateProtocol(body.protocol());
        Channel channel = new Channel();
        apply(channel, body);
        if (body.apiKey() == null || body.apiKey().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "API Key 不能为空");
        }
        channel.setApiKeyEnc(cipher.encrypt(body.apiKey()));
        // 上游账号键：保存时计算（明文在手）；容量取管理端填的并发数（未填走默认）
        channel.setAccountKey(accountRegistry.computeKey(channel.getBaseUrl(), body.apiKey()));
        accountRegistry.ensureAccount(channel.getAccountKey());
        if (body.maxConcurrency() != null) {
            accountRegistry.setCapacity(channel.getAccountKey(), body.maxConcurrency());
        }
        LocalDateTime now = LocalDateTime.now();
        channel.setEnabled(1);
        channel.setStatus(ChannelHealthTracker.NORMAL);
        channel.setCreatedAt(now);
        channel.setUpdatedAt(now);
        channelMapper.insert(channel);
        configService.refresh();
        return ApiResponse.ok(channel.getId());
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @Validated @RequestBody ChannelUpsertRequest body) {
        validateProtocol(body.protocol());
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "渠道不存在");
        }
        String oldAccountKey = channel.getAccountKey();
        apply(channel, body);
        // 账号键重算：新 Key 用明文；未重传 Key 但改了 base_url 也可能换账号（用存量密文解密计算）
        String keySource = body.apiKey() != null && !body.apiKey().isBlank()
                ? body.apiKey() : cipher.decrypt(channel.getApiKeyEnc());
        channel.setAccountKey(accountRegistry.computeKey(channel.getBaseUrl(), keySource));
        accountRegistry.ensureAccount(channel.getAccountKey());
        boolean accountChanged = oldAccountKey != null && !oldAccountKey.equals(channel.getAccountKey());
        if (accountChanged) {
            // 换 Key/换地址 = 换账号：把管理员配过的容量带过去，避免悄悄回默认值
            int carried = accountRegistry.capacityOf(oldAccountKey);
            if (carried > 0 && accountRegistry.capacityOf(channel.getAccountKey()) == 0) {
                accountRegistry.setCapacity(channel.getAccountKey(), carried);
            }
        }
        if (body.maxConcurrency() != null) {
            accountRegistry.setCapacity(channel.getAccountKey(), body.maxConcurrency());
        }
        channel.setUpdatedAt(LocalDateTime.now());
        channelMapper.updateById(channel);
        configService.refresh();
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("accountChanged", accountChanged);
        if (accountChanged) {
            result.put("notice", "上游身份已变化（Key 或 base_url 变更），并发容量已迁移到新账号池，请确认各渠道共享关系");
        }
        return ApiResponse.ok(result);
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<Void> enable(@PathVariable Long id) {
        channelOps.enable(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, String> body) {
        channelOps.disable(id, body == null ? "手动停用" : body.getOrDefault("reason", "手动停用"));
        return ApiResponse.ok();
    }

    /** 关停批次列表（账号级批量停用的待恢复记录，余额充值后整批恢复用）。 */
    @GetMapping("/disable-batches")
    public ApiResponse<List<Map<String, Object>>> pendingBatches(@RequestParam(required = false) String accountKey) {
        var batches = channelOps.pendingBatches(accountKey);
        return ApiResponse.ok(batches.stream().map(b -> {
            Map<String, Object> m = new java.util.HashMap<String, Object>();
            m.put("id", b.getId());
            m.put("accountKey", AccountRegistry.shortKey(b.getAccountKey()));
            m.put("reason", b.getReason());
            m.put("createdAt", b.getCreatedAt());
            m.put("restoredAt", b.getRestoredAt());
            return m;
        }).toList());
    }

    /** 整批恢复：恢复该批次全部渠道与联动停用的分组成员。 */
    @PostMapping("/disable-batches/{id}/restore")
    public ApiResponse<Void> restoreBatch(@PathVariable Long id) {
        channelOps.restoreBatch(id);
        return ApiResponse.ok();
    }

    public record ProbeRequest(@NotBlank String protocol, @NotBlank String baseUrl, @NotBlank String apiKey) {
    }

    /** 试跑（未保存的渠道也可用）：真实调一次上游验证连通与 Key。 */
    @PostMapping("/probe")
    public ApiResponse<Map<String, String>> probeUnsaved(@Validated @RequestBody ProbeRequest body) {
        validateProtocol(body.protocol());
        Channel temp = new Channel();
        temp.setName("__probe__");
        temp.setProtocol(body.protocol());
        temp.setBaseUrl(body.baseUrl().replaceAll("/+$", ""));
        temp.setApiKeyEnc(cipher.encrypt(body.apiKey()));
        temp.setMaxConcurrency(1);
        temp.setEnabled(1);
        temp.setStatus(ChannelHealthTracker.NORMAL);
        return ApiResponse.ok(Map.of("result", probe(temp)));
    }

    /** 试跑：真实调一次上游验证连通与 Key。 */
    @PostMapping("/{id}/test-run")
    public ApiResponse<Map<String, String>> testRun(@PathVariable Long id) {
        Channel channel = mustGet(id);
        return ApiResponse.ok(Map.of("result", probe(channel)));
    }

    /** 同步模型清单（OpenAI 族 GET /models；Crun 无此接口需人工录入）。 */
    @PostMapping("/{id}/sync-models")
    public ApiResponse<Map<String, Integer>> syncModels(@PathVariable Long id) {
        Channel channel = mustGet(id);
        ProtocolFamily family = ProtocolFamily.valueOf(channel.getProtocol());
        if (family == ProtocolFamily.CRUN_TASK || family == ProtocolFamily.VOLCANO_ARK) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该协议族没有模型查询接口，请在模型管理里人工录入");
        }
        UpstreamHttp.HttpResult result = http.getJson(
                com.drawbridge.adapter.Pointer.joinUrl(channel.getBaseUrl(), "/models"),
                Map.of("Authorization", "Bearer " + configService.runtimeOf(channel).apiKey()), 15_000);
        JsonNode node = UpstreamHttp.parseBody(result.status(), result.body());
        if (result.status() != 200) {
            throw ErrorClassifierBridge.fromOpenAi(result.status(), node);
        }
        JsonNode data = node.has("data") && node.get("data").isArray() ? node.get("data") : node;
        int count = 0;
        LocalDateTime now = LocalDateTime.now();
        for (JsonNode item : data) {
            String modelId = item.path("id").asText(null);
            if (modelId == null || modelId.isBlank()) {
                continue;
            }
            UpstreamModel existing = upstreamModelMapper.selectOne(Wrappers.<UpstreamModel>lambdaQuery()
                    .eq(UpstreamModel::getChannelId, id)
                    .eq(UpstreamModel::getModelCode, modelId));
            if (existing == null) {
                UpstreamModel m = new UpstreamModel();
                m.setChannelId(id);
                m.setModelCode(modelId);
                m.setSource(1);
                m.setEnabled(1);
                m.setCreatedAt(now);
                m.setUpdatedAt(now);
                upstreamModelMapper.insert(m);
            } else {
                existing.setEnabled(1);
                existing.setUpdatedAt(now);
                upstreamModelMapper.updateById(existing);
            }
            count++;
        }
        return ApiResponse.ok(Map.of("count", count));
    }

    @GetMapping("/{id}/models")
    public ApiResponse<List<UpstreamModel>> models(@PathVariable Long id) {
        return ApiResponse.ok(upstreamModelMapper.selectList(Wrappers.<UpstreamModel>lambdaQuery()
                .eq(UpstreamModel::getChannelId, id)
                .orderByAsc(UpstreamModel::getModelCode)));
    }

    public record ModelUpsertRequest(@NotBlank String modelCode) {
    }

    /** 人工录入模型（Crun 用）。 */
    @PostMapping("/{id}/models")
    public ApiResponse<Long> addModel(@PathVariable Long id, @Validated @RequestBody ModelUpsertRequest body) {
        mustGet(id);
        UpstreamModel existing = upstreamModelMapper.selectOne(Wrappers.<UpstreamModel>lambdaQuery()
                .eq(UpstreamModel::getChannelId, id)
                .eq(UpstreamModel::getModelCode, body.modelCode()));
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setEnabled(1);
            existing.setUpdatedAt(now);
            upstreamModelMapper.updateById(existing);
            return ApiResponse.ok(existing.getId());
        }
        UpstreamModel m = new UpstreamModel();
        m.setChannelId(id);
        m.setModelCode(body.modelCode());
        m.setSource(2);
        m.setEnabled(1);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        upstreamModelMapper.insert(m);
        return ApiResponse.ok(m.getId());
    }

    public record ModelImportRequest(@NotBlank String content) {
    }

    /** 批量导入模型：支持每行一个 / Markdown 列表（含链接） / JSON 字符串数组。 */
    @PostMapping("/{id}/models/import")
    public ApiResponse<Map<String, Integer>> importModels(@PathVariable Long id,
                                                          @Validated @RequestBody ModelImportRequest body) {
        mustGet(id);
        List<String> models = parseModelList(body.content());
        if (models.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未解析到任何模型名");
        }
        if (models.size() > 200) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单次最多导入 200 个模型");
        }
        LocalDateTime now = LocalDateTime.now();
        int added = 0;
        for (String code : models) {
            UpstreamModel existing = upstreamModelMapper.selectOne(Wrappers.<UpstreamModel>lambdaQuery()
                    .eq(UpstreamModel::getChannelId, id)
                    .eq(UpstreamModel::getModelCode, code));
            if (existing == null) {
                UpstreamModel m = new UpstreamModel();
                m.setChannelId(id);
                m.setModelCode(code);
                m.setSource(2);
                m.setEnabled(1);
                m.setCreatedAt(now);
                m.setUpdatedAt(now);
                upstreamModelMapper.insert(m);
                added++;
            } else if (existing.getEnabled() == null || existing.getEnabled() != 1) {
                existing.setEnabled(1);
                existing.setUpdatedAt(now);
                upstreamModelMapper.updateById(existing);
                added++;
            }
        }
        return ApiResponse.ok(Map.of("total", models.size(), "added", added));
    }

    /** 解析模型清单：JSON 数组 / 每行一个（容忍 Markdown 列表符、序号、链接）。 */
    static List<String> parseModelList(String content) {
        String t = content == null ? "" : content.trim();
        List<String> result = new ArrayList<>();
        if (t.startsWith("[")) {
            com.fasterxml.jackson.databind.JsonNode arr = com.drawbridge.common.JsonUtil.readTree(t);
            if (arr.isArray()) {
                arr.forEach(n -> {
                    if (n.isTextual() && !n.asText().isBlank()) {
                        result.add(n.asText().trim());
                    }
                });
            }
            return result.stream().distinct().toList();
        }
        for (String line : t.split("\r?\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("<")) {
                continue;
            }
            s = s.replaceAll("^[-*+]\\s+", "");
            s = s.replaceAll("^\\d+[.)]\\s*", "");
            if (s.startsWith("[") && s.contains("](")) {
                s = s.substring(1, s.indexOf("]("));
            }
            s = s.trim();
            if (!s.isEmpty() && s.matches("[\\w./:@+-]+")) {
                result.add(s);
            }
        }
        return result.stream().distinct().toList();
    }

    @DeleteMapping("/{id}/models/{modelId}")
    public ApiResponse<Void> deleteModel(@PathVariable Long id, @PathVariable Long modelId) {
        upstreamModelMapper.delete(Wrappers.<UpstreamModel>lambdaQuery()
                .eq(UpstreamModel::getId, modelId)
                .eq(UpstreamModel::getChannelId, id));
        return ApiResponse.ok();
    }

    // ------------------------------------------------------------------ 内部

    private void apply(Channel channel, ChannelUpsertRequest body) {
        channel.setName(body.name());
        channel.setProtocol(body.protocol());
        channel.setBaseUrl(body.baseUrl().replaceAll("/+$", ""));
        channel.setPathOverrides(body.pathOverrides() == null || body.pathOverrides().isEmpty()
                ? null : com.drawbridge.common.JsonUtil.toJson(body.pathOverrides()));
        channel.setExtraConfig(body.extraConfig() == null || body.extraConfig().isEmpty()
                ? null : com.drawbridge.common.JsonUtil.toJson(body.extraConfig()));
        channel.setMaxConcurrency(body.maxConcurrency());
        channel.setRemark(body.remark());
    }

    private Channel mustGet(Long id) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "渠道不存在");
        }
        return channel;
    }

    private void validateProtocol(String protocol) {
        try {
            ProtocolFamily.valueOf(protocol);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知协议族: " + protocol);
        }
    }

    /** 按协议探测：连通 + Key 有效性。 */
    private String probe(Channel channel) {
        ProtocolFamily family = ProtocolFamily.valueOf(channel.getProtocol());
        try {
            String key = configService.runtimeOf(channel).apiKey();
            UpstreamHttp.HttpResult result = switch (family) {
                case OPENAI_IMAGES_SYNC, OPENAI_ASYNC_TASK -> http.getJson(
                        com.drawbridge.adapter.Pointer.joinUrl(channel.getBaseUrl(), "/models"),
                        Map.of("Authorization", "Bearer " + key), 15_000);
                case CRUN_TASK -> http.getJson(
                        com.drawbridge.adapter.Pointer.joinUrl(channel.getBaseUrl(),
                                "/client/job/TaskInfo?task_id=__drawbridge_probe__"),
                        Map.of("x-api-key", key), 15_000);
                case VOLCANO_ARK -> throw new BizException(ErrorCode.PARAM_INVALID,
                        "火山方舟为视频协议，生图阶段未接入");
            };
            if (result.status() == 200) {
                return "连通正常，Key 有效";
            }
            if (result.status() == 401 || result.status() == 403) {
                return "可达，但 Key 无效或无权限（HTTP " + result.status() + "）";
            }
            if (family == ProtocolFamily.CRUN_TASK && result.status() >= 400 && result.status() < 500) {
                // Crun 先鉴权后验参数（实测：无 Key/假 Key 都是 401），探活任务号不存在回 4xx 属预期
                return "连通正常，Key 有效（探活任务号不存在 HTTP " + result.status() + "，属预期）";
            }
            return "返回异常（HTTP " + result.status() + "），请检查 base_url";
        } catch (BizException e) {
            throw e;
        } catch (com.drawbridge.adapter.UpstreamCallException e) {
            return "不可达或超时: " + e.getMessage();
        }
    }

    /** 复用分类器把 /models 探测错误转业务异常（管理端提示用）。 */
    static final class ErrorClassifierBridge {
        static BizException fromOpenAi(int status, JsonNode node) {
            String msg = node.path("error").path("message").asText(
                    node.path("message").asText("上游返回 HTTP " + status));
            return new BizException(ErrorCode.PARAM_INVALID, "同步模型失败: " + msg);
        }
    }
}
