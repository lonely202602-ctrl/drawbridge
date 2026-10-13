package com.drawbridge.adapter;

import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.http.RefImageFetcher;
import com.drawbridge.infrastructure.http.UpstreamHttp;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 协议族⑤（Gemini 原生 generateContent，apiyi nano 系）适配器测试：WireMock 模拟真实上游。
 * 响应解析重点：parts 异构数组混排时遍历取最后一张 inlineData；200 无图段=审核拦截终态。
 */
class GeminiNativeProviderTest {

    private WireMockServer wm;
    private GeminiNativeProvider provider;
    private ChannelConfigService.ChannelRuntime rt;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        provider = new GeminiNativeProvider(new UpstreamHttp(), new RefImageFetcher());
        rt = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl(), "sk-nano-key", Map.of(), Map.of());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private ImageProvider.SubmitCommand cmd() {
        return new ImageProvider.SubmitCommand("gemini-3-pro-image-preview", "a cat", 1,
                null, null, null, null, null, 10_000);
    }

    private String genUrl() {
        return "/v1beta/models/gemini-3-pro-image-preview:generateContent";
    }

    @Test
    void mixedPartsTakeLastImageAsFinal() {
        // 官方实测警告：parts 段数与顺序不保证，复杂任务混多张思考中间稿——最后一张才是终稿
        wm.stubFor(post(urlEqualTo(genUrl()))
                .withHeader("Authorization", containing("sk-nano-key"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"candidates":[{"content":{"parts":[
                            {"text":"这是过程说明"},
                            {"inlineData":{"mimeType":"image/png","data":"QUFBQQ=="}},
                            {"inlineData":{"mimeType":"image/jpeg","data":"QkFBQQ=="}}
                        ]},"finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":100}}
                        """)));
        ImageProvider.SubmitOutcome.Completed done = assertInstanceOf(
                ImageProvider.SubmitOutcome.Completed.class, provider.submit(cmd(), rt));
        assertTrue(done.inlineB64());
        // mimeType 以响应为准（jpeg 不是猜的 png）
        assertEquals(List.of("data:image/jpeg;base64,QkFBQQ=="), done.resultUrls());
        wm.verify(1, postRequestedFor(urlEqualTo(genUrl())));
    }

    @Test
    void requestShapeTextPlusImageConfig() {
        wm.stubFor(post(urlEqualTo(genUrl()))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"QQ==\"}}]}}]}")));
        ImageProvider.SubmitCommand tuned = new ImageProvider.SubmitCommand("gemini-3-pro-image-preview", "画海", 1,
                "2k", "16:9", null, null, null, 10_000);
        provider.submit(tuned, rt);
        String body = wm.findAll(postRequestedFor(urlEqualTo(genUrl()))).get(0).getBodyAsString();
        // 请求形状：1 个 text part + responseModalities + imageConfig（档位大写）
        assertTrue(body.contains("\"parts\":[{\"text\":\"画海\"}]") || body.contains("\"text\":\"画海\""), body);
        assertTrue(body.contains("\"responseModalities\":[\"IMAGE\"]"), body);
        assertTrue(body.contains("\"aspectRatio\":\"16:9\""), body);
        assertTrue(body.contains("\"imageSize\":\"2K\""), body);
        // n 是网关契约（恒 1），不进 Gemini 请求体
        assertFalse(body.contains("\"n\""), body);
    }

    @Test
    void emptyPartsMeansContentRejected() {
        // 200 + finishReason=SAFETY + 无图片段：审核拦截，终态（文案带 finishReason）
        wm.stubFor(post(urlEqualTo(genUrl()))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"candidates\":[{\"content\":{\"parts\":null},\"finishReason\":\"SAFETY\"}]}")));
        try {
            provider.submit(cmd(), rt);
            fail("应抛出 CONTENT_REJECTED");
        } catch (UpstreamCallException e) {
            assertEquals(UpstreamErrorKind.CONTENT_REJECTED, e.getKind());
            assertTrue(e.getMessage().contains("SAFETY"));
        }
    }

    @Test
    void refImagesFetchedAndInlinedAsBase64() {
        // 参考图默认代下载转 base64 inlineData（官方推荐路线）
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01};
        wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/refs/a.jpg"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withBody(jpeg)));
        wm.stubFor(post(urlEqualTo(genUrl()))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"QQ==\"}}]}}]}")));
        ImageProvider.SubmitCommand withImg = new ImageProvider.SubmitCommand(
                "gemini-3-pro-image-preview", "改图", 1, null, null, null,
                List.of(wm.baseUrl() + "/refs/a.jpg"), null, 10_000);
        provider.submit(withImg, rt);
        String body = wm.findAll(postRequestedFor(urlEqualTo(genUrl()))).get(0).getBodyAsString();
        String expectB64 = Base64.getEncoder().encodeToString(jpeg);
        // Content-Type 头不认账时按魔数判型（octet-stream → jpeg）
        assertTrue(body.contains("\"inlineData\":{\"mimeType\":\"image/jpeg\",\"data\":\"" + expectB64 + "\"}"), body);
    }

    @Test
    void fileUriStylePassesUrlDirectlyWithoutDownload() {
        ChannelConfigService.ChannelRuntime fileUri = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl(), "k", Map.of(), Map.of("refImageStyle", "gemini_file_uri"));
        wm.stubFor(post(urlEqualTo(genUrl()))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"QQ==\"}}]}}]}")));
        ImageProvider.SubmitCommand withImg = new ImageProvider.SubmitCommand(
                "gemini-3-pro-image-preview", "改图", 1, null, null, null,
                List.of("https://cdn.example.com/pic.png"), null, 10_000);
        provider.submit(withImg, fileUri);
        String body = wm.findAll(postRequestedFor(urlEqualTo(genUrl()))).get(0).getBodyAsString();
        // fileUri 直传（驼峰命名），mimeType 从扩展名猜；不走网关下载
        assertTrue(body.contains("\"fileData\":{\"fileUri\":\"https://cdn.example.com/pic.png\",\"mimeType\":\"image/png\"}"), body);
        assertFalse(body.contains("inlineData\":"), body);
    }

    @Test
    void imageSizeContract() {
        // 第一代模型不支持 imageSize：omitImageSize=true 时档位不发
        ChannelConfigService.ChannelRuntime gen1 = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl(), "k", Map.of(), Map.of("omitImageSize", true));
        ImageProvider.SubmitCommand tier = new ImageProvider.SubmitCommand(
                "gemini-2.5-flash-image", "p", 1, "4k", "9:16", null, null, null, 10_000);
        String body = provider.buildRequestJson(tier, gen1);
        assertFalse(body.contains("\"imageSize\""), body);
        assertTrue(body.contains("\"aspectRatio\":\"9:16\""), body);

        // 未传比例/档位 → 不带 imageConfig（模型用默认：编辑场景保持参考图方向）
        String bare = provider.buildRequestJson(cmd(), rt);
        assertFalse(bare.contains("\"imageConfig\""), bare);

        // 旧版 size 兜底换算：1792x1024 → 16:9 + 2K
        ImageProvider.SubmitCommand legacy = new ImageProvider.SubmitCommand(
                "gemini-3-pro-image-preview", "p", 1, null, null, "1792x1024", null, null, 10_000);
        String converted = provider.buildRequestJson(legacy, rt);
        assertTrue(converted.contains("\"aspectRatio\":\"16:9\""), converted);
        assertTrue(converted.contains("\"imageSize\":\"2K\""), converted);
    }

    @Test
    void httpErrorsClassified() {
        wm.stubFor(post(urlEqualTo(genUrl()))
                .willReturn(aResponse().withStatus(429)
                        .withBody("{\"error\":{\"code\":429,\"message\":\"rate limit\",\"status\":\"RESOURCE_EXHAUSTED\"}}")));
        expectKind(cmd(), UpstreamErrorKind.RATE_LIMITED);

        wm.resetAll();
        wm.stubFor(post(urlEqualTo(genUrl()))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"error\":{\"code\":400,\"message\":\"Invalid value at 'generationConfig.imageConfig.imageSize'\",\"status\":\"INVALID_ARGUMENT\"}}")));
        expectKind(cmd(), UpstreamErrorKind.PARAM_INVALID);

        wm.resetAll();
        wm.stubFor(post(urlEqualTo(genUrl()))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}")));
        expectKind(cmd(), UpstreamErrorKind.UPSTREAM_SERVER_ERROR);
    }

    private void expectKind(ImageProvider.SubmitCommand cmd, UpstreamErrorKind expected) {
        try {
            provider.submit(cmd, rt);
            fail("应抛出 UpstreamCallException");
        } catch (UpstreamCallException e) {
            assertEquals(expected, e.getKind());
        }
    }

    @Test
    void modelPlaceholderAndPathOverride() {
        String url = provider.submitUrl(cmd(), rt);
        assertEquals(wm.baseUrl() + genUrl(), url);

        ChannelConfigService.ChannelRuntime override = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl(), "k", Map.of("submitPath", "/v1beta/models/{model}:generate"), Map.of());
        assertEquals(wm.baseUrl() + "/v1beta/models/gemini-3-pro-image-preview:generate",
                provider.submitUrl(cmd(), override));
    }

    @Test
    void optionsPassThroughTopLevelButStructureKeysBlocked() {
        ImageProvider.SubmitCommand withOpts = new ImageProvider.SubmitCommand(
                "gemini-3-pro-image-preview", "p", 1, null, null, null, null,
                Map.of("safetySettings", List.of(), "contents", "HACK", "generationConfig", "HACK"), 10_000);
        String body = provider.buildRequestJson(withOpts, rt);
        // safetySettings 直通体顶层（Gemini 专属字段）；contents/generationConfig 是结构性键不许后门
        assertTrue(body.contains("\"safetySettings\":[]"), body);
        assertFalse(body.contains("HACK"), body);
    }
}
