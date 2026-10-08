package com.drawbridge.adapter;

import com.drawbridge.common.JsonUtil;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.http.UpstreamHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 协议族④：Crun 私有任务协议（x-api-key 鉴权，{code,message,data} 壳）。
 * POST {base}/client/job/CreateTask → data.task_id
 * GET  {base}/client/job/TaskInfo?task_id= → data.status: pending/running/success/failed，
 *      成功取 data.result.media_urls，成本取 data.credits。
 * 路径可由渠道 path_overrides 覆盖（默认与 docs.crun.ai 一致）。
 */
@Component
public class CrunTaskProvider implements ImageProvider {

    private static final Pattern SIZE = Pattern.compile("^(\\d{3,5})x(\\d{3,5})$");
    private static final Set<String> RESERVED_INPUT = Set.of("modelVariant", "prompt", "imgUrls");

    private final UpstreamHttp http;

    public CrunTaskProvider(UpstreamHttp http) {
        this.http = http;
    }

    @Override
    public ProtocolFamily family() {
        return ProtocolFamily.CRUN_TASK;
    }

    @Override
    public SubmitOutcome submit(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        String body = buildRequestJson(cmd, rt);
        String url = Pointer.joinUrl(rt.baseUrl(),
                rt.pathOverrides().getOrDefault("submitPath", "/client/job/CreateTask"));
        UpstreamHttp.HttpResult result = http.postJson(url, authHeaders(rt), body, cmd.timeoutMs());
        JsonNode node = UpstreamHttp.parseBody(result.status(), result.body());
        if (result.status() != 200) {
            throw ErrorClassifier.fromOpenAi(result.status(), node);
        }
        int code = node.path("code").asInt(0);
        if (code != 200) {
            throw ErrorClassifier.fromCrunCode(code, node.path("message").asText("Crun 未知错误"));
        }
        String taskId = node.path("data").path("task_id").asText(null);
        if (taskId == null || taskId.isBlank()) {
            throw new UpstreamCallException(UpstreamErrorKind.UNKNOWN,
                    "Crun CreateTask 成功但缺少 task_id", result.status());
        }
        return new SubmitOutcome.Accepted(taskId, result.body());
    }

    @Override
    public PollOutcome poll(String upstreamTaskId, ChannelConfigService.ChannelRuntime rt) {
        String path = rt.pathOverrides().getOrDefault("queryPath", "/client/job/TaskInfo")
                + "?task_id=" + java.net.URLEncoder.encode(upstreamTaskId, java.nio.charset.StandardCharsets.UTF_8);
        String url = Pointer.joinUrl(rt.baseUrl(), path);
        UpstreamHttp.HttpResult result = http.getJson(url, authHeaders(rt), 10_000);
        JsonNode node = UpstreamHttp.parseBody(result.status(), result.body());
        if (result.status() != 200) {
            throw ErrorClassifier.fromOpenAi(result.status(), node);
        }
        int code = node.path("code").asInt(0);
        if (code != 200) {
            throw ErrorClassifier.fromCrunCode(code, node.path("message").asText("Crun 未知错误"));
        }
        JsonNode data = node.path("data");
        String status = data.path("status").asText("");
        return switch (status) {
            case "success" -> {
                List<String> urls = new ArrayList<>();
                JsonNode mediaUrls = data.path("result").path("media_urls");
                if (mediaUrls.isArray()) {
                    mediaUrls.forEach(u -> {
                        if (u.isTextual() && !u.asText().isBlank()) {
                            urls.add(u.asText());
                        }
                    });
                }
                if (urls.isEmpty()) {
                    yield PollOutcome.failed("Crun 任务成功但未返回 media_urls", result.body());
                }
                BigDecimal credits = data.path("credits").isNumber()
                        ? data.path("credits").decimalValue() : null;
                yield PollOutcome.succeeded(urls, false, result.body(), credits);
            }
            case "failed" -> PollOutcome.failed(
                    data.path("result").path("message").asText("Crun 生成失败"), result.body());
            default -> PollOutcome.running(result.body());
        };
    }

    @Override
    public String buildRequestJson(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        ObjectNode input = JsonUtil.MAPPER.createObjectNode();
        input.put("prompt", cmd.prompt() == null ? "" : cmd.prompt());
        // gpt-image-2-5 家族接受 model_variant（flare 快/sunburst 细节强）
        if (cmd.upstreamModel() != null && cmd.upstreamModel().contains("gpt-image-2-5")) {
            String variant = cmd.options() != null && cmd.options().get("modelVariant") instanceof String v
                    && !v.isBlank() ? v : "flare";
            input.put("model_variant", variant);
        }
        if (cmd.imageUrls() != null && !cmd.imageUrls().isEmpty()) {
            ArrayNode urls = input.putArray("img_urls");
            cmd.imageUrls().forEach(urls::add);
        }
        if (cmd.aspectRatio() != null && !cmd.aspectRatio().isBlank()) {
            input.put("aspect_ratio", cmd.aspectRatio());
        }
        String resolution = resolutionOf(cmd.size());
        if (resolution != null) {
            input.put("resolution", resolution);
        }
        if (cmd.options() != null) {
            for (Map.Entry<String, Object> e : cmd.options().entrySet()) {
                if (!RESERVED_INPUT.contains(e.getKey()) && e.getValue() != null) {
                    input.set(e.getKey(), JsonUtil.MAPPER.valueToTree(e.getValue()));
                }
            }
        }
        ObjectNode body = JsonUtil.MAPPER.createObjectNode();
        body.put("model", cmd.upstreamModel());
        body.set("input", input);
        return body.toString();
    }

    /** size（WxH）→ Crun resolution：1k/2k/4k（长边 4096+ 为 4k，2048+ 为 2k）。 */
    private String resolutionOf(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        Matcher m = SIZE.matcher(size.trim());
        if (!m.matches()) {
            return null;
        }
        int longSide = Math.max(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        if (longSide >= 4096) {
            return "4k";
        }
        return longSide >= 2048 ? "2k" : "1k";
    }

    private Map<String, String> authHeaders(ChannelConfigService.ChannelRuntime rt) {
        return Map.of("x-api-key", rt.apiKey());
    }
}
