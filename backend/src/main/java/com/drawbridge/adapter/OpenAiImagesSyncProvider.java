package com.drawbridge.adapter;

import com.drawbridge.common.JsonUtil;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.http.UpstreamHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 协议族①：OpenAI Images 同步（apiyi / laozhang / uuapi / nezhagate 的同步生图模型）。
 * POST {base}/images/generations → 2xx 直接返回 data[].url 或 data[].b64_json。
 * 路径可由渠道 path_overrides.submitPath 覆盖。
 */
@Component
public class OpenAiImagesSyncProvider implements ImageProvider {

    /** options 里不允许直接覆盖的保留键。 */
    private static final Set<String> RESERVED = Set.of(
            "model", "prompt", "n", "size", "image", "images", "response_format", "modelVariant");

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
                rt.pathOverrides().getOrDefault("submitPath", "/images/generations"));
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
        if (cmd.imageUrls() != null && !cmd.imageUrls().isEmpty()) {
            if (cmd.imageUrls().size() == 1) {
                body.put("image", cmd.imageUrls().getFirst());
            } else {
                ArrayNode arr = body.putArray("image");
                cmd.imageUrls().forEach(arr::add);
            }
        }
        if (cmd.options() != null) {
            for (Map.Entry<String, Object> e : cmd.options().entrySet()) {
                if (!RESERVED.contains(e.getKey()) && e.getValue() != null) {
                    body.set(e.getKey(), JsonUtil.MAPPER.valueToTree(e.getValue()));
                }
            }
        }
        return body.toString();
    }

    private Map<String, String> authHeaders(ChannelConfigService.ChannelRuntime rt) {
        return Map.of("Authorization", "Bearer " + rt.apiKey());
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
