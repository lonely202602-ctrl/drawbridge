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

/**
 * 协议族④：Crun 私有任务协议（x-api-key 鉴权，{code,message,data} 壳）。
 * POST {base}/client/job/CreateTask → data.task_id
 * GET  {base}/client/job/TaskInfo?task_id= → data.status: pending/running/success/failed，
 *      成功取 data.result.media_urls，成本取 data.credits。
 * 路径可由渠道 path_overrides 覆盖（默认与 docs.crun.ai 一致）。
 */
@Component
public class CrunTaskProvider implements ImageProvider {

    /** resolution/aspect_ratio/size 是网关契约键，由网关直译，options 不许绕过 */
    private static final Set<String> RESERVED_INPUT = Set.of("modelVariant", "prompt", "imgUrls",
            "resolution", "aspect_ratio", "aspectRatio", "size");

    private final UpstreamHttp http;

    public CrunTaskProvider(UpstreamHttp http) {
        this.http = http;
    }

    @Override
    public ProtocolFamily family() {
        return ProtocolFamily.CRUN_TASK;
    }

    @Override
    public String submitUrl(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        return Pointer.joinUrl(rt.baseUrl(),
                rt.pathOverrides().getOrDefault("submitPath", "/client/job/CreateTask"));
    }

    @Override
    public SubmitOutcome submit(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        com.fasterxml.jackson.databind.node.ObjectNode body = buildBody(cmd, rt);
        String url = submitUrl(cmd, rt);
        Map<String, String> headers = authHeaders(rt);

        // estimate-credits 免费预检：提前暴露参数错误，resolution 大小写自动纠正
        body = prevalidate(url, headers, body, cmd.timeoutMs(), rt);

        UpstreamHttp.HttpResult result = http.postJson(url, headers, body.toString(), cmd.timeoutMs());
        JsonNode node = UpstreamHttp.parseBody(result.status(), result.body());
        if (result.status() != 200) {
            // HTTP 422 的壳里是 message+errors 明细，优先按 Crun 语义透出
            if (node.hasNonNull("message") || node.hasNonNull("code")) {
                throw ErrorClassifier.fromCrunCode(
                        node.path("code").asInt(result.status()), crunDetail(node));
            }
            throw ErrorClassifier.fromOpenAi(result.status(), node);
        }
        int code = node.path("code").asInt(0);
        if (code != 200) {
            throw ErrorClassifier.fromCrunCode(code, crunDetail(node));
        }
        String taskId = node.path("data").path("task_id").asText(null);
        if (taskId == null || taskId.isBlank()) {
            // 2xx 但没有任务号：上游可能已受理（代理回垃圾），不换渠道重发（防重复扣费）
            throw new UpstreamCallException(UpstreamErrorKind.BAD_RESPONSE,
                    "Crun CreateTask 成功但缺少 task_id", result.status());
        }
        return new SubmitOutcome.Accepted(taskId, result.body());
    }

    /**
     * estimate-credits 免费预检（Crun 各模型参数枚举不一致，如 resolution 大小写按模型而异）：
     * 业务码 422 且提示 resolution → 自动翻转大小写重估，通过则用纠正后的请求体提交；
     * 其它 422 把 errors 明细透出为 PARAM_INVALID；预检自身网络异常不阻断（以 CreateTask 真实响应为准）。
     */
    private com.fasterxml.jackson.databind.node.ObjectNode prevalidate(String createUrl,
            Map<String, String> headers, com.fasterxml.jackson.databind.node.ObjectNode body,
            int timeoutMs, ChannelConfigService.ChannelRuntime rt) {
        String estUrl = Pointer.joinUrl(rt.baseUrl(),
                rt.pathOverrides().getOrDefault("estimatePath", "/client/job/estimate-credits"));
        UpstreamCallException fatal = null;
        try {
            UpstreamHttp.HttpResult r = http.postJson(estUrl, headers, body.toString(),
                    Math.min(timeoutMs, 15000));
            // Crun 校验错误直接回 HTTP 422（body 里也有 code=422/errors 明细），两种形态都认
            JsonNode node = com.drawbridge.common.JsonUtil.readTree(r.body());
            boolean validationError = r.status() == 422 || node.path("code").asInt(0) == 422;
            if (validationError) {
                String detail = crunDetail(node);
                JsonNode input = body.path("input");
                if (detail.contains("resolution") && input.hasNonNull("resolution")) {
                    com.fasterxml.jackson.databind.node.ObjectNode flipped = body.deepCopy();
                    String res = input.path("resolution").asText();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) flipped.path("input"))
                            .put("resolution", flipCase(res));
                    UpstreamHttp.HttpResult r2 = http.postJson(estUrl, headers, flipped.toString(),
                            Math.min(timeoutMs, 15000));
                    boolean flipOk = r2.status() == 200
                            && com.drawbridge.common.JsonUtil.readTree(r2.body()).path("code").asInt(0) == 200;
                    if (flipOk) {
                        return flipped;
                    }
                }
                fatal = new UpstreamCallException(UpstreamErrorKind.PARAM_INVALID,
                        "Crun 参数错误: " + detail, r.status());
            }
        } catch (Exception ignore) {
            // 预检不可用（网络/响应异常）不阻断提交，以 CreateTask 真实响应为准
        }
        if (fatal != null) {
            throw fatal;
        }
        return body;
    }

    private static String flipCase(String resolution) {
        return resolution.equals(resolution.toLowerCase())
                ? resolution.toUpperCase(java.util.Locale.ROOT)
                : resolution.toLowerCase(java.util.Locale.ROOT);
    }

    /** Crun 422 的 errors 数组是精确定位（哪个字段为什么不行），拼进错误消息便于排障。 */
    private static String crunDetail(JsonNode node) {
        String message = node.path("message").asText("Crun 未知错误");
        JsonNode errors = node.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            StringBuilder sb = new StringBuilder(message).append(" [");
            errors.forEach(e -> sb.append(e.asText()).append("; "));
            return sb.substring(0, sb.length() - 2) + "]";
        }
        return message;
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
        // 官方状态枚举之外的取消态也认终态（statusMap 可再覆盖），不认就只会空转到任务超时
        if ("cancelled".equals(status) || "canceled".equals(status)) {
            return PollOutcome.failed("Crun 任务已取消", result.body());
        }
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
        return buildBody(cmd, rt).toString();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode buildBody(SubmitCommand cmd,
                                                                     ChannelConfigService.ChannelRuntime rt) {
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
        // 分辨率契约：Crun 原生认「比例+档位」，业务方显式传了才发（不传不带，模型用自己的默认）；
        // 旧版 size（deprecated）换算成比例+档位兜底，显式传值优先
        String ratio = cmd.aspectRatio();
        String tier = cmd.resolution();
        if ((ratio == null || ratio.isBlank()) && cmd.legacySize() != null) {
            String[] derived = Resolutions.crunSizeFromLegacy(cmd.legacySize());
            if (derived != null) {
                ratio = derived[0];
                if (tier == null || tier.isBlank()) {
                    tier = derived[1];
                }
            }
        }
        if (ratio != null && !ratio.isBlank()) {
            input.put("aspect_ratio", ratio);
        }
        if (tier != null && !tier.isBlank()) {
            input.put("resolution", tier);
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
        return body;
    }

    private Map<String, String> authHeaders(ChannelConfigService.ChannelRuntime rt) {
        return Map.of("x-api-key", rt.apiKey());
    }
}
