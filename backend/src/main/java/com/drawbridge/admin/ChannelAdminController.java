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
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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

    public record ChannelUpsertRequest(
            @NotBlank String name,
            @NotBlank String protocol,
            @NotBlank String baseUrl,
            String apiKey,
            Map<String, String> pathOverrides,
            Map<String, Object> extraConfig,
            Integer maxConcurrency,
            BigDecimal costPrice,
            String remark) {
    }

    public record ChannelView(Channel channel, boolean keyConfigured, int healthState, boolean cooling) {
    }

    @GetMapping
    public ApiResponse<List<ChannelView>> list() {
        List<ChannelView> views = channelMapper.selectList(
                        Wrappers.<Channel>lambdaQuery().orderByDesc(Channel::getId))
                .stream()
                .map(c -> new ChannelView(mask(c),
                        c.getApiKeyEnc() != null && !c.getApiKeyEnc().isBlank(),
                        healthTracker.healthState(c.getId()),
                        cooldownRegistry.isCooling(c.getId())))
                .toList();
        return ApiResponse.ok(views);
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
    public ApiResponse<Void> update(@PathVariable Long id, @Validated @RequestBody ChannelUpsertRequest body) {
        validateProtocol(body.protocol());
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "渠道不存在");
        }
        apply(channel, body);
        if (body.apiKey() != null && !body.apiKey().isBlank()) {
            channel.setApiKeyEnc(cipher.encrypt(body.apiKey()));
        }
        channel.setUpdatedAt(LocalDateTime.now());
        channelMapper.updateById(channel);
        configService.refresh();
        return ApiResponse.ok();
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
        channel.setCostPrice(body.costPrice());
        channel.setRemark(body.remark());
    }

    private Channel mustGet(Long id) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "渠道不存在");
        }
        return channel;
    }

    /** 渠道脱敏视图（不回传密钥密文）。 */
    private Channel mask(Channel c) {
        Channel copy = new Channel();
        org.springframework.beans.BeanUtils.copyProperties(c, copy);
        copy.setApiKeyEnc(null);
        return copy;
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
