package com.drawbridge.adapter;

import com.drawbridge.common.JsonUtil;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.http.UpstreamHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 协议族②：OpenAI 风格异步任务（nezhagate 202+jobs / uuapi async+tasks）。
 * 提交返回任务号 → 轮询查询直到成功/失败。
 * 字段位置与状态映射内置两套默认（flavor=nezha/uu），渠道 extra_config 可逐项覆盖：
 * submitPath / queryPath / idField / statusField / urlField / statusMap。
 */
@Slf4j
@Component
public class OpenAiAsyncTaskProvider implements ImageProvider {

    private static final Set<String> RESERVED = Set.of(
            "model", "prompt", "n", "size", "resolution", "aspectRatio", "image", "images", "modelVariant");

    private final UpstreamHttp http;

    public OpenAiAsyncTaskProvider(UpstreamHttp http) {
        this.http = http;
    }

    @Override
    public ProtocolFamily family() {
        return ProtocolFamily.OPENAI_ASYNC_TASK;
    }

    @Override
    public String submitUrl(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        return Pointer.joinUrl(rt.baseUrl(), submitPath(cmd, rt));
    }

    @Override
    public SubmitOutcome submit(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        String body = buildRequestJson(cmd, rt);
        String url = submitUrl(cmd, rt);
        UpstreamHttp.HttpResult result = http.postJson(url, authHeaders(rt), body, cmd.timeoutMs());
        JsonNode node = UpstreamHttp.parseBody(result.status(), result.body());
        if (result.status() >= 400) {
            throw ErrorClassifier.fromOpenAi(result.status(), node);
        }
        String taskId = firstNonBlank(node,
                field(rt, "idField"),
                "task_id", "id");
        if (taskId == null) {
            // 防御自愈：没拿到任务号，但响应是同步生图形状（data[].url / b64_json）——
            // 多半是异步适配器被指到了同步端点（渠道协议配错）。图已付费生成，收下而不是丢弃。
            List<String> syncUrls = collectSyncImageResults(node);
            if (!syncUrls.isEmpty()) {
                log.warn("异步上游未返回任务号但返回了同步生图结果，已按同步完成收下"
                        + "（渠道协议可能配错，请检查）: base={}", rt.baseUrl());
                boolean inline = syncUrls.stream().anyMatch(u -> u.startsWith("data:"));
                return new SubmitOutcome.Completed(syncUrls, inline, result.body(), null);
            }
            // 2xx 但没有任务号：上游可能已受理（代理回垃圾），不换渠道重发（防重复扣费）
            throw new UpstreamCallException(UpstreamErrorKind.BAD_RESPONSE,
                    "异步上游受理但未解析到任务号（HTTP " + result.status() + "）", result.status());
        }
        return new SubmitOutcome.Accepted(taskId, result.body());
    }

    @Override
    public PollOutcome poll(String upstreamTaskId, ChannelConfigService.ChannelRuntime rt) {
        String path = field(rt, "queryPath").replace("{id}", upstreamTaskId);
        String url = Pointer.joinUrl(rt.baseUrl(), path);
        UpstreamHttp.HttpResult result = http.getJson(url, authHeaders(rt), 10_000);
        JsonNode node = UpstreamHttp.parseBody(result.status(), result.body());
        if (result.status() >= 400) {
            throw ErrorClassifier.fromOpenAi(result.status(), node);
        }
        String status = Pointer.text(node, field(rt, "statusField"));
        String mapped = status == null ? "" : normalize(mapStatus(rt, status));
        return switch (mapped) {
            case "succeeded" -> {
                List<String> urls = collectUrls(node, field(rt, "urlField"));
                boolean inline = false;
                if (urls.isEmpty()) {
                    // 部分模型任务成功但只回 b64_json：转 data URI 透传收下（纯网关不落盘，业务方自行转存），
                    // 不再判"成功但未解析到 URL"把已付费的图丢掉；格式按魔数嗅探，不盲拼 png
                    for (JsonNode b : Pointer.resolveAll(node, "data[*].b64_json")) {
                        if (b.isTextual() && !b.asText().isBlank()) {
                            urls.add("data:image/" + B64Mime.sniff(b.asText()) + ";base64," + b.asText());
                            inline = true;
                        }
                    }
                }
                if (urls.isEmpty()) {
                    yield PollOutcome.failed("上游任务成功但未解析到结果 URL/b64", result.body());
                }
                yield PollOutcome.succeeded(urls, inline, result.body(), null);
            }
            case "failed" -> PollOutcome.failed(failMessage(node), result.body());
            default -> PollOutcome.running(result.body());
        };
    }

    @Override
    public String buildRequestJson(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        ObjectNode body = JsonUtil.MAPPER.createObjectNode();
        body.put("model", cmd.upstreamModel());
        if (cmd.prompt() != null) {
            body.put("prompt", cmd.prompt());
        }
        if (cmd.n() > 0 && cmd.n() != 1) {
            body.put("n", cmd.n());
        }
        // 分辨率契约：业务方显式传了比例才发 size（没传不带=模型用自己的默认，修图不裁方图）；
        // 旧版 size（deprecated）原样透传优先。
        // requestStyle=ratioTier（APIMart 风格）：size 直传比例串（16:9），档位走独立 resolution 字段
        if ("ratioTier".equals(rt.extra().get("requestStyle"))) {
            String legacy = cmd.legacySize();
            if (legacy != null && !legacy.isBlank() && !"auto".equalsIgnoreCase(legacy)) {
                body.put("size", legacy);
            } else if (cmd.aspectRatio() != null && !cmd.aspectRatio().isBlank()) {
                body.put("size", cmd.aspectRatio());
            }
            if (cmd.resolution() != null && !cmd.resolution().isBlank()) {
                body.put("resolution", cmd.resolution());
            }
        } else {
            String size = Resolutions.openAiSize(cmd.aspectRatio(), cmd.resolution(), cmd.legacySize(), rt.extra());
            if (size != null && !size.isBlank()) {
                body.put("size", size);
            }
        }
        RefImages.applyTo(body, cmd.imageUrls(), rt);
        if (cmd.options() != null) {
            for (Map.Entry<String, Object> e : cmd.options().entrySet()) {
                if (!RESERVED.contains(e.getKey()) && e.getValue() != null) {
                    body.set(e.getKey(), JsonUtil.MAPPER.valueToTree(e.getValue()));
                }
            }
        }
        return body.toString();
    }

    // ------------------------------------------------------------------ 字段配置

    /** flavor 内置默认 + 渠道覆盖。 */
    private String field(ChannelConfigService.ChannelRuntime rt, String name) {
        Object override = rt.extra().get(name);
        if (override instanceof String s && !s.isBlank()) {
            return s;
        }
        String flavor = rt.extra().get("flavor") instanceof String f ? f.toLowerCase(Locale.ROOT) : "nezha";
        return switch (name) {
            case "submitPath" -> "uu".equals(flavor) ? "/images/generations/async" : "/images/generations";
            case "queryPath" -> "uu".equals(flavor) ? "/images/tasks/{id}" : "/images/jobs/{id}";
            case "idField" -> "uu".equals(flavor) ? "task_id" : "id";
            case "statusField" -> "status";
            case "urlField" -> "data[*].url";
            default -> throw new IllegalArgumentException("未知字段: " + name);
        };
    }

    @SuppressWarnings("unchecked")
    private String mapStatus(ChannelConfigService.ChannelRuntime rt, String upstreamStatus) {
        Object override = rt.extra().get("statusMap");
        if (override instanceof Map<?, ?> map && map.get(upstreamStatus) instanceof String s) {
            return s;
        }
        return switch (normalize(upstreamStatus)) {
            case "queued", "processing", "pending", "running", "in_progress" -> "RUNNING";
            case "succeeded", "completed", "success" -> "SUCCEEDED";
            case "failed", "failure", "cancelled", "canceled" -> "FAILED";
            default -> "RUNNING";
        };
    }

    private List<String> collectUrls(JsonNode node, String urlField) {
        List<String> urls = new ArrayList<>();
        for (JsonNode n : Pointer.resolveAll(node, urlField)) {
            if (n.isTextual() && !n.asText().isBlank()) {
                urls.add(n.asText());
            }
        }
        return urls;
    }

    /** 识别同步生图形状的响应（data[].url 或 data[].b64_json）；b64 按魔数嗅探转 data URI。 */
    private List<String> collectSyncImageResults(JsonNode node) {
        List<String> urls = new ArrayList<>();
        for (JsonNode item : node.path("data")) {
            if (item.hasNonNull("url") && !item.path("url").asText().isBlank()) {
                urls.add(item.get("url").asText());
            } else if (item.hasNonNull("b64_json") && !item.path("b64_json").asText().isBlank()) {
                urls.add("data:image/" + B64Mime.sniff(item.get("b64_json").asText())
                        + ";base64," + item.get("b64_json").asText());
            }
        }
        return urls;
    }

    private String failMessage(JsonNode node) {
        // data.error.message：APIMart 统一任务壳的失败原因位置
        for (String p : new String[]{"data.error.message", "error.message", "error", "message", "fail_message"}) {
            String t = Pointer.text(node, p);
            if (t != null) {
                return t;
            }
        }
        return "上游任务失败";
    }

    private String firstNonBlank(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = Pointer.text(node, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private Map<String, String> authHeaders(ChannelConfigService.ChannelRuntime rt) {
        return Map.of("Authorization", "Bearer " + rt.apiKey());
    }

    /**
     * 提交路径：端点选择由请求形状决定——带参考图的 uuapi 风格渠道固定走 edits 系端点
     * （路径可用 path_overrides.editsSubmitPath 覆盖）；path_overrides.submitPath 只管文生图。
     */
    private String submitPath(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        if (RefImages.uuapiEdits(rt, cmd.imageUrls())) {
            return pathOverride(rt, "editsSubmitPath", "/images/edits/async");
        }
        String override = rt.pathOverrides().get("submitPath");
        if (override != null && !override.isBlank()) {
            return override;
        }
        return field(rt, "submitPath");
    }

    private String pathOverride(ChannelConfigService.ChannelRuntime rt, String key, String fallback) {
        String v = rt.pathOverrides().get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
