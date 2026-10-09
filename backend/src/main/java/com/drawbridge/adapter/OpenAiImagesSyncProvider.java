package com.drawbridge.adapter;

import com.drawbridge.common.JsonUtil;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.http.UpstreamHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 协议族①：OpenAI Images 同步（apiyi / laozhang / uuapi / nezhagate 的同步生图模型）。
 * POST {base}/images/generations → 2xx 直接返回 data[].url 或 data[].b64_json。
 * 图生图：默认把参考图 URL 放 body.image 直传；渠道 extra_config.refImageStyle=uuapi 时
 * 切到 POST {base}/images/edits、参考图用 images[].image_url 包裹（uuapi 的图生图契约）。
 * 提交路径可由渠道 path_overrides.submitPath 覆盖（覆盖后不再按参考图自动切换）。
 */
@Component
public class OpenAiImagesSyncProvider implements ImageProvider {

    /** options 里不允许直接覆盖的保留键。response_format 放行：业务方可要求上游回 url 而非 b64。 */
    private static final Set<String> RESERVED = Set.of(
            "model", "prompt", "n", "size", "image", "images", "modelVariant");

    private final UpstreamHttp http;

    public OpenAiImagesSyncProvider(UpstreamHttp http) {
        this.http = http;
    }

    @Override
    public ProtocolFamily family() {
        return ProtocolFamily.OPENAI_IMAGES_SYNC;
    }

    @Override
    public SubmitOutcome submit(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        String body = buildRequestJson(cmd, rt);
        String url = Pointer.joinUrl(rt.baseUrl(),
                rt.pathOverrides().getOrDefault("submitPath", defaultSubmitPath(cmd, rt)));
        UpstreamHttp.HttpResult result = http.postJson(url, authHeaders(rt), body, cmd.timeoutMs());
        JsonNode node = UpstreamHttp.parseBody(result.status(), result.body());
        if (result.status() < 200 || result.status() >= 300) {
            throw ErrorClassifier.fromOpenAi(result.status(), node);
        }
        JsonNode data = node.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new UpstreamCallException(UpstreamErrorKind.UNKNOWN,
                    "同步上游返回成功但缺少 data 数组", result.status());
        }
        List<String> urls = new ArrayList<>();
        boolean inlineB64 = false;
        for (JsonNode item : data) {
            if (item.hasNonNull("url") && !item.path("url").asText().isBlank()) {
                urls.add(item.get("url").asText());
            } else if (item.hasNonNull("b64_json") && !item.path("b64_json").asText().isBlank()) {
                urls.add(dataUri(item.get("b64_json").asText(), cmd));
                inlineB64 = true;
            }
        }
        if (urls.isEmpty()) {
            throw new UpstreamCallException(UpstreamErrorKind.UNKNOWN,
                    "同步上游返回成功但未包含 url/b64_json", result.status());
        }
        return new SubmitOutcome.Completed(urls, inlineB64, result.body(), null);
    }

    @Override
    public PollOutcome poll(String upstreamTaskId, ChannelConfigService.ChannelRuntime rt) {
        throw new UnsupportedOperationException("同步协议族不支持轮询");
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
        // 渠道级默认响应格式：业务方未显式指定时代为注入（显式传入优先）
        if (!body.has("response_format")
                && rt.extra().get("defaultResponseFormat") instanceof String fmt && !fmt.isBlank()) {
            body.put("response_format", fmt);
        }
        return body.toString();
    }

    private Map<String, String> authHeaders(ChannelConfigService.ChannelRuntime rt) {
        return Map.of("Authorization", "Bearer " + rt.apiKey());
    }

    /** 默认提交路径：显式 submitPath 覆盖优先级最高（在 submit 里处理），这里只管模板默认。 */
    private String defaultSubmitPath(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        return RefImages.uuapiEdits(rt, cmd.imageUrls()) ? "/images/edits" : "/images/generations";
    }

    /** b64 → data URI，业务方可直接用于 <img>；格式取 options.output_format，默认 png。 */
    private String dataUri(String b64, SubmitCommand cmd) {
        String fmt = "png";
        if (cmd.options() != null && cmd.options().get("output_format") instanceof String s && !s.isBlank()) {
            fmt = s;
        }
        return "data:image/" + fmt + ";base64," + b64;
    }
}
