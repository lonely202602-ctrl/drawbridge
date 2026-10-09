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
            "model", "prompt", "n", "size", "image", "images", "modelVariant");

    private final UpstreamHttp http;

    public OpenAiAsyncTaskProvider(UpstreamHttp http) {
        this.http = http;
    }

    @Override
    public ProtocolFamily family() {
        return ProtocolFamily.OPENAI_ASYNC_TASK;
    }

    @Override
    public SubmitOutcome submit(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        String body = buildRequestJson(cmd, rt);
        String url = Pointer.joinUrl(rt.baseUrl(), submitPath(cmd, rt));
        UpstreamHttp.HttpResult result = http.postJson(url, authHeaders(rt), body, cmd.timeoutMs());
        JsonNode node = UpstreamHttp.parseBody(result.status(), result.body());
        if (result.status() >= 500) {
            throw ErrorClassifier.fromOpenAi(result.status(), node);
        }
        if (result.status() == 401 || result.status() == 403 || result.status() == 429) {
            throw ErrorClassifier.fromOpenAi(result.status(), node);
        }
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
            throw new UpstreamCallException(UpstreamErrorKind.UNKNOWN,
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
                if (urls.isEmpty()) {
                    yield PollOutcome.failed("上游任务成功但未解析到结果 URL", result.body());
                }
                yield PollOutcome.succeeded(urls, false, result.body(), null);
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
        if (cmd.size() != null && !cmd.size().isBlank()) {
            body.put("size", cmd.size());
        }
        RefImages.applyTo(body, cmd.imageUrls(), RefImages.uuapiEdits(rt, cmd.imageUrls()));
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

    /** 识别同步生图形状的响应（data[].url 或 data[].b64_json）；b64 按 png 转 data URI。 */
    private List<String> collectSyncImageResults(JsonNode node) {
        List<String> urls = new ArrayList<>();
        for (JsonNode item : node.path("data")) {
            if (item.hasNonNull("url") && !item.path("url").asText().isBlank()) {
                urls.add(item.get("url").asText());
            } else if (item.hasNonNull("b64_json") && !item.path("b64_json").asText().isBlank()) {
                urls.add("data:image/png;base64," + item.get("b64_json").asText());
            }
        }
        return urls;
    }

    private String failMessage(JsonNode node) {
        for (String p : new String[]{"error.message", "error", "message", "fail_message"}) {
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

    /** 提交路径：显式覆盖（pathOverrides 优先，extra 次之）；UUAPI 参考图风格带图时走 /images/edits/async。 */
    private String submitPath(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        String override = rt.pathOverrides().get("submitPath");
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (RefImages.uuapiEdits(rt, cmd.imageUrls())) {
            return "/images/edits/async";
        }
        return field(rt, "submitPath");
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
