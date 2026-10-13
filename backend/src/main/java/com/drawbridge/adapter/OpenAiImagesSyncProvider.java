package com.drawbridge.adapter;

import com.drawbridge.common.JsonUtil;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.http.MultipartBody;
import com.drawbridge.infrastructure.http.RefImageFetcher;
import com.drawbridge.infrastructure.http.UpstreamHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 协议族①：OpenAI Images 同步（apiyi / laozhang / uuapi / nezhagate 的同步生图模型）。
 * POST {base}/images/generations → 2xx 直接返回 data[].url 或 data[].b64_json。
 * 图生图两条路（渠道 extra_config.refImageStyle 选择，端点选择不被 submitPath 覆盖短路）：
 *   uuapi        → 参考图包成 images[].image_url（JSON）；
 *   openai_edits → 网关代下载参考图，POST {base}/images/edits multipart 文件上传（apiyi/官方 gpt-image 系，
 *                  image[] 字段重复传入，文件名可用 extra_config.editsImageField 覆盖）。
 */
@Component
public class OpenAiImagesSyncProvider implements ImageProvider {

    /** options 里不允许直接覆盖的保留键（resolution/aspectRatio 是网关契约，由网关翻译成 size，不走后门）。 */
    private static final Set<String> RESERVED = Set.of(
            "model", "prompt", "n", "size", "resolution", "aspectRatio", "image", "images", "modelVariant");

    private final UpstreamHttp http;
    private final RefImageFetcher refImages;

    public OpenAiImagesSyncProvider(UpstreamHttp http, RefImageFetcher refImages) {
        this.http = http;
        this.refImages = refImages;
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
        UpstreamHttp.HttpResult result;
        if (RefImages.openAiEdits(rt, cmd.imageUrls())) {
            result = postMultipartEdits(cmd, rt);
        } else {
            result = http.postJson(submitUrl(cmd, rt), authHeaders(rt),
                    buildRequestJson(cmd, rt), cmd.timeoutMs());
        }
        return parseCompletion(result, cmd);
    }

    /** multipart 图生图：参考图代下载为文件字段，其余参数为普通表单字段（与 JSON 体同一套参数决策）。 */
    private UpstreamHttp.HttpResult postMultipartEdits(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", cmd.upstreamModel());
        if (cmd.prompt() != null) {
            fields.put("prompt", cmd.prompt());
        }
        // 表单值一律字符串化（multipart 无类型概念；options 里的数字/布尔透传同理）
        appendCommonParams((k, v) -> fields.put(k, String.valueOf(v)), cmd, rt);
        // 渠道级默认响应格式：业务方未显式指定时代为注入（显式传入优先，与 JSON 路径同一规则）
        if (!fields.containsKey("response_format")
                && rt.extra().get("defaultResponseFormat") instanceof String fmt && !fmt.isBlank()) {
            fields.put("response_format", fmt);
        }
        List<MultipartBody.FilePart> files = new ArrayList<>();
        String fieldName = rt.extra().get("editsImageField") instanceof String s && !s.isBlank() ? s : "image[]";
        for (String url : cmd.imageUrls()) {
            RefImageFetcher.Fetched img = refImages.fetch(url, Math.min(cmd.timeoutMs(), 30_000));
            files.add(new MultipartBody.FilePart(fieldName, img.filename(), img.contentType(), img.bytes()));
        }
        List<MultipartBody.Field> formFields = fields.entrySet().stream()
                .map(e -> new MultipartBody.Field(e.getKey(), e.getValue()))
                .toList();
        MultipartBody body = MultipartBody.of(formFields, files);
        return http.postMultipart(submitUrl(cmd, rt), authHeaders(rt), body, cmd.timeoutMs());
    }

    /** 2xx 响应 → 结果 URL 列表；url 与 b64_json 混排时按项各自取。 */
    private SubmitOutcome parseCompletion(UpstreamHttp.HttpResult result, SubmitCommand cmd) {
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
        appendCommonParams((k, v) -> body.set(k, JsonUtil.MAPPER.valueToTree(v)), cmd, rt);
        RefImages.applyTo(body, cmd.imageUrls(), rt);
        // 渠道级默认响应格式：业务方未显式指定时代为注入（显式传入优先）
        if (!body.has("response_format")
                && rt.extra().get("defaultResponseFormat") instanceof String fmt && !fmt.isBlank()) {
            body.put("response_format", fmt);
        }
        // multipart 图生图存档：标注模式与参考图来源（存档不触发下载，文件本体只在 submit 时拉取）
        if (RefImages.openAiEdits(rt, cmd.imageUrls())) {
            body.put("_mode", "multipart_edits");
            ArrayNode refs = body.putArray("_refImages");
            cmd.imageUrls().forEach(refs::add);
        }
        return body.toString();
    }

    /** JSON 体与 multipart 表单共用的参数决策：size + 参考/契约键之外的 options 透传。 */
    private void appendCommonParams(java.util.function.BiConsumer<String, Object> putter,
                                    SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        String size = Resolutions.openAiSize(cmd.aspectRatio(), cmd.resolution(), cmd.legacySize(), rt.extra());
        if (size != null && !size.isBlank()) {
            putter.accept("size", size);
        }
        if (cmd.options() != null) {
            for (Map.Entry<String, Object> e : cmd.options().entrySet()) {
                if (!RESERVED.contains(e.getKey()) && e.getValue() != null) {
                    putter.accept(e.getKey(), e.getValue());
                }
            }
        }
    }

    private Map<String, String> authHeaders(ChannelConfigService.ChannelRuntime rt) {
        return Map.of("Authorization", "Bearer " + rt.apiKey());
    }

    /**
     * 提交路径：端点选择由请求形状决定——uuapi / openai_edits 风格带参考图固定走图生图端点
     * （路径可用 path_overrides.editsSubmitPath 覆盖）；path_overrides.submitPath 只管文生图。
     */
    private String submitPath(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        if (cmd.imageUrls() != null && !cmd.imageUrls().isEmpty()
                && (RefImages.uuapiEdits(rt, cmd.imageUrls()) || RefImages.openAiEdits(rt, cmd.imageUrls()))) {
            String edits = rt.pathOverrides().get("editsSubmitPath");
            return edits == null || edits.isBlank() ? "/images/edits" : edits;
        }
        String override = rt.pathOverrides().get("submitPath");
        return override != null && !override.isBlank() ? override : "/images/generations";
    }

    /** b64 → data URI，业务方可直接用于 <img>；兼容历史版本带 data: 前缀的响应（官逆曾出现），格式按魔数嗅探。 */
    private String dataUri(String raw, SubmitCommand cmd) {
        String b64 = raw;
        if (b64.startsWith("data:")) {
            int comma = b64.indexOf(',');
            if (comma > 0) {
                b64 = b64.substring(comma + 1);
            }
        }
        String fmt = cmd.options() != null && cmd.options().get("output_format") instanceof String s && !s.isBlank()
                ? s : B64Mime.sniff(b64);
        return "data:image/" + fmt + ";base64," + b64;
    }
}
