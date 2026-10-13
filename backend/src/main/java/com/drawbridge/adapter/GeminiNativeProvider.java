package com.drawbridge.adapter;

import com.drawbridge.common.JsonUtil;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.http.RefImageFetcher;
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
 * 协议族⑤：Gemini 原生 generateContent（apiyi nano 系：gemini-3-pro-image(-preview) /
 * gemini-3.1-flash-image-preview / gemini-nano-banana-2.1 / gemini-3.1-flash-lite-image /
 * gemini-2.5-flash-image）。POST {base}/v1beta/models/{model}:generateContent，Bearer 鉴权，同步返回。
 *
 * 请求：contents[0].parts = 1 个 text part + N 个图片 part（每个 part 只能 text 或图片二选一，不可并存）；
 *       generationConfig.responseModalities=["IMAGE"] + imageConfig{aspectRatio, imageSize(1K/2K/4K 大写)}。
 * 参考图两条路（渠道 extra_config.refImageStyle）：
 *   默认               → 网关代下载转 base64 inlineData（官方推荐，稳定）；
 *   gemini_file_uri    → 直传 fileData.fileUri（省流量，但非全球 CDN 图床会被谷歌静默不参考）。
 * 渠道 extra_config.omitImageSize=true 供第一代 gemini-2.5-flash-image（不支持 imageSize，传了必败）。
 *
 * 响应坑（官方文档实测警告）：candidates[0].content.parts 是异构数组、段数与顺序都不保证，
 * 复杂任务会混入 2-10 张思考中间稿——必须遍历筛 inlineData 取最后一张（终稿），mimeType 以响应为准。
 * HTTP 200 + 无任何图片段 = 内容审核拦截（finishReason=PROHIBITED_CONTENT/SAFETY 等），归终态不重试。
 */
@Component
public class GeminiNativeProvider implements ImageProvider {

    /**
     * options 透传直通响应体顶层（safetySettings / tools 等 Gemini 专属字段）；
     * contents/generationConfig 是适配器组装的结构性键，不允许从后门覆盖。
     */
    private static final Set<String> RESERVED = Set.of(
            "model", "prompt", "n", "size", "resolution", "aspectRatio", "image", "images",
            "modelVariant", "contents", "generationConfig");

    private final UpstreamHttp http;
    private final RefImageFetcher refImages;

    public GeminiNativeProvider(UpstreamHttp http, RefImageFetcher refImages) {
        this.http = http;
        this.refImages = refImages;
    }

    @Override
    public ProtocolFamily family() {
        return ProtocolFamily.GEMINI_NATIVE;
    }

    @Override
    public String submitUrl(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        String path = rt.pathOverrides().get("submitPath");
        if (path == null || path.isBlank()) {
            path = "/v1beta/models/{model}:generateContent";
        }
        return Pointer.joinUrl(rt.baseUrl(), path.replace("{model}", cmd.upstreamModel()));
    }

    @Override
    public SubmitOutcome submit(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        // 参考图 base64 风格在此代下载（存档走 buildRequestJson 的占位标注，不重复下载）
        List<RefImageFetcher.Fetched> fetched = null;
        boolean fileUri = "gemini_file_uri".equals(rt.extra().get("refImageStyle"));
        if (cmd.imageUrls() != null && !cmd.imageUrls().isEmpty() && !fileUri) {
            fetched = new ArrayList<>();
            for (String url : cmd.imageUrls()) {
                fetched.add(refImages.fetch(url, Math.min(cmd.timeoutMs(), 30_000)));
            }
        }
        String url = submitUrl(cmd, rt);
        UpstreamHttp.HttpResult result = http.postJson(url, authHeaders(rt),
                buildBody(cmd, rt, fetched).toString(), cmd.timeoutMs());
        return parseCompletion(result);
    }

    /** 2xx 响应 → 遍历 parts 取最后一张 inlineData；无图段=审核拦截（终态）。 */
    private SubmitOutcome parseCompletion(UpstreamHttp.HttpResult result) {
        JsonNode node = UpstreamHttp.parseBody(result.status(), result.body());
        if (result.status() < 200 || result.status() >= 300) {
            // Gemini 错误壳 {error:{code,message,status}} 与 OpenAI 同形，fromOpenAi 的状态码/文案分类直接适用
            throw ErrorClassifier.fromOpenAi(result.status(), node);
        }
        JsonNode candidate = node.path("candidates").path(0);
        JsonNode parts = candidate.path("content").path("parts");
        JsonNode lastImage = null;
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.hasNonNull("inlineData") && !part.path("inlineData").path("data").asText("").isBlank()) {
                    lastImage = part.get("inlineData");
                }
            }
        }
        if (lastImage == null) {
            // 200 但没有图片段：审核拦截（不计费/原样重试常能过，但网关不重试敏感词，终态回给业务方改写）
            String reason = candidate.path("finishReason").asText("");
            if (reason.isBlank()) {
                reason = node.path("promptFeedback").path("blockReason").asText("UNKNOWN");
            }
            throw new UpstreamCallException(UpstreamErrorKind.CONTENT_REJECTED,
                    "上游未返回图片（内容拦截 finishReason=" + reason + "）", result.status());
        }
        String b64 = lastImage.path("data").asText();
        String mime = lastImage.path("mimeType").asText("");
        if (mime == null || mime.isBlank()) {
            mime = "image/" + B64Mime.sniff(b64);
        }
        return new SubmitOutcome.Completed(List.of("data:" + mime + ";base64," + b64), true, result.body(), null);
    }

    @Override
    public PollOutcome poll(String upstreamTaskId, ChannelConfigService.ChannelRuntime rt) {
        throw new UnsupportedOperationException("同步协议族不支持轮询");
    }

    @Override
    public String buildRequestJson(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt) {
        // 存档路径不触发参考图下载：base64 风格的图片段以 <REF:url> 占位标注，真实请求体见 submit
        return buildBody(cmd, rt, null).toString();
    }

    /** 请求体组装；fetched=null 时 base64 图片段以占位串标注（仅存档路径）。 */
    private ObjectNode buildBody(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt,
                                 List<RefImageFetcher.Fetched> fetched) {
        ObjectNode body = JsonUtil.MAPPER.createObjectNode();
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        if (cmd.prompt() != null && !cmd.prompt().isBlank()) {
            parts.addObject().put("text", cmd.prompt());
        }
        boolean fileUri = "gemini_file_uri".equals(rt.extra().get("refImageStyle"));
        if (cmd.imageUrls() != null) {
            for (int i = 0; i < cmd.imageUrls().size(); i++) {
                String url = cmd.imageUrls().get(i);
                if (fileUri) {
                    ObjectNode fileData = parts.addObject().putObject("fileData");
                    fileData.put("fileUri", url);
                    String mime = mimeFromExtension(url);
                    if (mime != null) {
                        fileData.put("mimeType", mime);
                    }
                } else if (fetched != null && i < fetched.size()) {
                    RefImageFetcher.Fetched img = fetched.get(i);
                    ObjectNode inline = parts.addObject().putObject("inlineData");
                    inline.put("mimeType", img.contentType());
                    inline.put("data", java.util.Base64.getEncoder().encodeToString(img.bytes()));
                } else {
                    ObjectNode inline = parts.addObject().putObject("inlineData");
                    inline.put("mimeType", mimeFromExtension(url) == null ? "image/png" : mimeFromExtension(url));
                    inline.put("data", "<REF:" + url + ">");
                }
            }
        }

        ObjectNode config = body.putObject("generationConfig");
        config.putArray("responseModalities").add("IMAGE");
        String aspectRatio = aspectRatioOf(cmd);
        String imageSize = imageSizeOf(cmd, rt.extra());
        if (aspectRatio != null || imageSize != null) {
            ObjectNode imageConfig = config.putObject("imageConfig");
            if (aspectRatio != null) {
                imageConfig.put("aspectRatio", aspectRatio);
            }
            if (imageSize != null) {
                imageConfig.put("imageSize", imageSize);
            }
        }
        if (cmd.options() != null) {
            for (Map.Entry<String, Object> e : cmd.options().entrySet()) {
                if (!RESERVED.contains(e.getKey()) && e.getValue() != null) {
                    body.set(e.getKey(), JsonUtil.MAPPER.valueToTree(e.getValue()));
                }
            }
        }
        return body;
    }

    /** 显式比例优先；旧版 size 兜底换算（1792x1024 → 16:9，复用 Crun 系换算表）。 */
    private String aspectRatioOf(SubmitCommand cmd) {
        if (cmd.aspectRatio() != null && !cmd.aspectRatio().isBlank()) {
            return cmd.aspectRatio();
        }
        String[] fromLegacy = Resolutions.crunSizeFromLegacy(cmd.legacySize());
        return fromLegacy == null ? null : fromLegacy[0];
    }

    /** 档位 → imageSize（nano 系要大写 1K/2K/4K）；渠道 omitImageSize=true 跳过（第一代模型不支持）。 */
    private String imageSizeOf(SubmitCommand cmd, Map<String, Object> extra) {
        if (Resolutions.truthy(extra.get("omitImageSize"))) {
            return null;
        }
        String tier = cmd.resolution();
        if ((tier == null || tier.isBlank()) && cmd.legacySize() != null && !cmd.legacySize().isBlank()) {
            String[] fromLegacy = Resolutions.crunSizeFromLegacy(cmd.legacySize());
            tier = fromLegacy == null ? null : fromLegacy[1];
        }
        if (tier == null || tier.isBlank() || "auto".equalsIgnoreCase(tier)) {
            return null;
        }
        return tier.toUpperCase();
    }

    private Map<String, String> authHeaders(ChannelConfigService.ChannelRuntime rt) {
        return Map.of("Authorization", "Bearer " + rt.apiKey());
    }

    /** fileUri 风格的 mimeType 从 URL 扩展名猜；猜不出不传（上游自行识别）。 */
    private String mimeFromExtension(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        String path = lower.split("\\?")[0];
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (path.endsWith(".webp")) {
            return "image/webp";
        }
        if (path.endsWith(".gif")) {
            return "image/gif";
        }
        return null;
    }
}
