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
 * 图生图：渠道 extra_config.refImageStyle=uuapi 时带参考图自动切 POST {base}/images/edits、
 * 参考图用 images[].image_url 包裹（uuapi 的图生图契约），端点选择不被 submitPath 覆盖短路。
 */
@Component
public class OpenAiImagesSyncProvider implements ImageProvider {

    /** options 里不允许直接覆盖的保留键（resolution/aspectRatio 是网关契约，由网关翻译成 size，不走后门）。 */
    private static final Set<String> RESERVED = Set.of(
            "model", "prompt", "n", "size", "resolution", "aspectRatio", "image", "images", "modelVariant");

    private final UpstreamHttp http;

    public OpenAiImagesSyncProvider(UpstreamHttp http) {
        this.http = http;
    }

    @Override
    public ProtocolFamily family() {
        return ProtocolFamily.OPENAI_IMAGES_SYNC;
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
        if (result.status() < 200 || result.status() >= 300) {
            throw ErrorClassifier.fromOpenAi(result.status(), node);
        }
        JsonNode data = node.path("data");
        if (!data.isArray() || data.isEmpty()) {
            // 2xx 但响应形状不对：上游可能已出图（代理回垃圾），不换渠道重发（防重复扣费）
            throw new UpstreamCallException(UpstreamErrorKind.BAD_RESPONSE,
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
            throw new UpstreamCallException(UpstreamErrorKind.BAD_RESPONSE,
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
        // 分辨率契约：业务方显式传了比例才发 size（没传不带=模型用自己的默认，修图不裁方图）；
        // 旧版 size（deprecated）原样透传优先
        String size = Resolutions.openAiSize(cmd.aspectRatio(), cmd.resolution(), cmd.legacySize(), rt.extra());
        if (size != null && !size.isBlank()) {
            body.put("size", size);
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

    /**
     * 提交路径：端点选择由请求形状决定——带参考图的 uuapi 风格渠道固定走 /images/edits
     * （路径可用 path_overrides.editsSubmitPath 覆盖）；path_overrides.submitPath 只管文生图。
     */
    private String submitPath(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        if (RefImages.uuapiEdits(rt, cmd.imageUrls())) {
            String edits = rt.pathOverrides().get("editsSubmitPath");
            return edits == null || edits.isBlank() ? "/images/edits" : edits;
        }
        String override = rt.pathOverrides().get("submitPath");
        return override != null && !override.isBlank() ? override : "/images/generations";
    }

    /** b64 → data URI，业务方可直接用于 <img>；格式取 options.output_format，否则按魔数嗅探。 */
    private String dataUri(String b64, SubmitCommand cmd) {
        String fmt = cmd.options() != null && cmd.options().get("output_format") instanceof String s && !s.isBlank()
                ? s : B64Mime.sniff(b64);
        return "data:image/" + fmt + ";base64," + b64;
    }
}
