package com.drawbridge.adapter;

import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.http.UpstreamHttp;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * 协议族①（OpenAI Images 同步）适配器测试：WireMock 模拟真实上游。
 */
class OpenAiImagesSyncProviderTest {

    private WireMockServer wm;
    private OpenAiImagesSyncProvider provider;
    private ChannelConfigService.ChannelRuntime rt;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        provider = new OpenAiImagesSyncProvider(new UpstreamHttp());
        rt = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl() + "/v1", "sk-upstream-key", Map.of(), Map.of());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private ImageProvider.SubmitCommand cmd() {
        return new ImageProvider.SubmitCommand("gpt-image-2.5", "a cat", 1,
                null, null, null, null, null, 10_000);
    }

    @Test
    void submitReturnsUrls() {
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-upstream-key"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"created":1,"data":[{"url":"https://cdn.example.com/a.png"},{"url":"https://cdn.example.com/b.png"}]}
                        """)));
        ImageProvider.SubmitOutcome outcome = provider.submit(cmd(), rt);
        ImageProvider.SubmitOutcome.Completed completed = assertInstanceOf(
                ImageProvider.SubmitOutcome.Completed.class, outcome);
        assertEquals(List.of("https://cdn.example.com/a.png", "https://cdn.example.com/b.png"),
                completed.resultUrls());
        assertFalse(completed.inlineB64());
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/images/generations")));
    }

    @Test
    void b64BecomesInlineDataUri() {
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"data":[{"b64_json":"aGVsbG8="}]}
                        """)));
        ImageProvider.SubmitOutcome.Completed completed = assertInstanceOf(
                ImageProvider.SubmitOutcome.Completed.class, provider.submit(cmd(), rt));
        assertTrue(completed.inlineB64());
        assertEquals(List.of("data:image/png;base64,aGVsbG8="), completed.resultUrls());
    }

    @Test
    void upstreamErrorsClassified() {
        // 429
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(429)
                        .withBody("{\"error\":{\"message\":\"rate limit exceeded\"}}")));
        expectKind(cmd(), rt, provider, UpstreamErrorKind.RATE_LIMITED);

        // 403 + insufficient_quota（上游没米）
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(403)
                        .withBody("{\"error\":{\"message\":\"quota exceeded\",\"code\":\"insufficient_quota\"}}")));
        expectKind(cmd(), rt, provider, UpstreamErrorKind.BALANCE_EXHAUSTED);

        // 内容审核
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"error\":{\"message\":\"content_policy_violation: unsafe prompt\"}}")));
        expectKind(cmd(), rt, provider, UpstreamErrorKind.CONTENT_REJECTED);

        // 5xx
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}")));
        expectKind(cmd(), rt, provider, UpstreamErrorKind.UPSTREAM_SERVER_ERROR);
    }

    private static void expectKind(ImageProvider.SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt,
                                   OpenAiImagesSyncProvider provider, UpstreamErrorKind expected) {
        try {
            provider.submit(cmd, rt);
            fail("应抛出 UpstreamCallException");
        } catch (UpstreamCallException e) {
            assertEquals(expected, e.getKind());
        }
    }

    @Test
    void buildRequestRespectsReservedKeysAndPassthrough() {
        ImageProvider.SubmitCommand cmd = new ImageProvider.SubmitCommand("m1", "p", 2,
                "1k", "16:9", null, List.of("https://r.png"),
                Map.of("quality", "high", "model", "HACK", "prompt", "HACK", "modelVariant", "x",
                        "size", "HACK", "resolution", "HACK", "aspectRatio", "HACK"),
                10_000);
        String json = provider.buildRequestJson(cmd, rt);
        assertTrue(json.contains("\"quality\":\"high\""));
        assertTrue(json.contains("\"image\":\"https://r.png\""));
        // 保留键不允许通过 options 覆盖（n=2 才写 n）
        assertTrue(json.contains("\"n\":2"));
        assertTrue(json.contains("\"model\":\"m1\""));
        assertTrue(json.contains("\"prompt\":\"p\""));
        // 分辨率契约：16:9 横版归档，档位在该族降级；契约键不可被 options 绕过
        assertTrue(json.contains("\"size\":\"1536x1024\""));
        assertFalse(json.contains("HACK"));
    }

    @Test
    void resolutionContractDirectionArchiveAndSizeMap() {
        // 没传比例 → 不带 size（模型用自己的默认，修图不裁方图）
        String noRatio = provider.buildRequestJson(cmd(), rt);
        assertFalse(noRatio.contains("\"size\""), noRatio);
        // 竖版归档
        ImageProvider.SubmitCommand portrait = new ImageProvider.SubmitCommand(
                "m", "p", 1, "4k", "9:16", null, null, null, 10_000);
        assertTrue(provider.buildRequestJson(portrait, rt).contains("\"size\":\"1024x1536\""),
                "竖版归档 1024x1536（4k 在该族降级为最好一档）");
        // sizeMap 字符串 = 该档位整表覆盖（管理员显式配置的固定尺寸，横竖一律此值）
        ChannelConfigService.ChannelRuntime mapped = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl() + "/v1", "k", Map.of(),
                Map.of("sizeMap", Map.of("2k", "2048x2048")));
        ImageProvider.SubmitCommand tier2k = new ImageProvider.SubmitCommand(
                "m", "p", 1, "2k", "16:9", null, null, null, 10_000);
        assertTrue(provider.buildRequestJson(tier2k, mapped).contains("\"size\":\"2048x2048\""),
                "渠道 sizeMap 字符串=整表覆盖");
        // sizeMap 对象 = 按方向配（横竖不反的完整兑现）
        ChannelConfigService.ChannelRuntime byDir = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl() + "/v1", "k", Map.of(),
                Map.of("sizeMap", Map.of("2k", Map.of(
                        "landscape", "2048x1152", "portrait", "1152x2048", "square", "2048x2048"))));
        assertTrue(provider.buildRequestJson(tier2k, byDir).contains("\"size\":\"2048x1152\""),
                "sizeMap 按方向：16:9 → landscape");
        ImageProvider.SubmitCommand tier2kPortrait = new ImageProvider.SubmitCommand(
                "m", "p", 1, "2k", "9:16", null, null, null, 10_000);
        assertTrue(provider.buildRequestJson(tier2kPortrait, byDir).contains("\"size\":\"1152x2048\""),
                "sizeMap 按方向：9:16 → portrait");
    }

    @Test
    void legacySizePassthroughWinsOverArchive() {
        // 旧版 size（deprecated）：原样透传——1792x1024（dall-e-3 横版）不做有损换算
        ImageProvider.SubmitCommand legacy = new ImageProvider.SubmitCommand(
                "m", "p", 1, null, null, "1792x1024", null, null, 10_000);
        assertTrue(provider.buildRequestJson(legacy, rt).contains("\"size\":\"1792x1024\""));
        // 显式传了比例也以旧 size 为准（老调用方语义不变）
        ImageProvider.SubmitCommand legacyWithRatio = new ImageProvider.SubmitCommand(
                "m", "p", 1, "1k", "16:9", "1792x1024", null, null, 10_000);
        String json = provider.buildRequestJson(legacyWithRatio, rt);
        assertTrue(json.contains("\"size\":\"1792x1024\""), json);
        assertFalse(json.contains("1536x1024"), json);
    }

    @Test
    void pathOverrideHonored() {
        ChannelConfigService.ChannelRuntime override = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl() + "/v1", "k", Map.of("submitPath", "/images/custom"), Map.of());
        wm.stubFor(post(urlEqualTo("/v1/images/custom"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"url\":\"https://x/a.png\"}]}")));
        ImageProvider.SubmitOutcome outcome = provider.submit(cmd(), override);
        assertInstanceOf(ImageProvider.SubmitOutcome.Completed.class, outcome);
    }

    @Test
    void uuapiEditsStyleRoutesReferenceImagesToEditsEndpoint() {
        ChannelConfigService.ChannelRuntime uu = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl() + "/v1", "k", Map.of(), Map.of("refImageStyle", "uuapi"));
        ImageProvider.SubmitCommand withImgs = new ImageProvider.SubmitCommand("gpt-image-2.5", "美化", 1,
                null, null, null, List.of("https://r1.png", "https://r2.png"), null, 10_000);

        // 带参考图 → 打 /images/edits，参考图用 images[].image_url 包裹
        wm.stubFor(post(urlEqualTo("/v1/images/edits"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}")));
        ImageProvider.SubmitOutcome.Completed done = assertInstanceOf(
                ImageProvider.SubmitOutcome.Completed.class, provider.submit(withImgs, uu));
        assertTrue(done.inlineB64());
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/images/edits")));
        String body = wm.findAll(postRequestedFor(urlEqualTo("/v1/images/edits"))).get(0).getBodyAsString();
        assertTrue(body.contains(
                "\"images\":[{\"image_url\":\"https://r1.png\"},{\"image_url\":\"https://r2.png\"}]"), body);
        assertFalse(body.contains("\"image\":"), body);

        // 无参考图 → 仍走同步文生图 generations
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"url\":\"https://x/a.png\"}]}")));
        assertInstanceOf(ImageProvider.SubmitOutcome.Completed.class, provider.submit(cmd(), uu));
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/images/generations")));

        // 默认风格（未配 refImageStyle）带参考图 → 老行为：image 字段直传 generations
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"url\":\"https://x/b.png\"}]}")));
        provider.submit(withImgs, rt);
        String legacyBody = wm.findAll(postRequestedFor(urlEqualTo("/v1/images/generations")))
                .get(0).getBodyAsString();
        assertTrue(legacyBody.contains("\"image\":[\"https://r1.png\",\"https://r2.png\"]"), legacyBody);
    }

    @Test
    void editsSwitchWinsOverSubmitPathOverride() {
        // 回归：path_overrides.submitPath 只管文生图；带参考图的 uuapi 渠道仍必须切 edits
        ChannelConfigService.ChannelRuntime shadowed = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl() + "/v1", "k", Map.of("submitPath", "/images/generations"),
                Map.of("refImageStyle", "uuapi"));
        ImageProvider.SubmitCommand withImg = new ImageProvider.SubmitCommand("gpt-image-2.5", "美化", 1,
                null, null, null, List.of("https://r1.png"), null, 10_000);
        wm.stubFor(post(urlEqualTo("/v1/images/edits"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"url\":\"https://x/e.png\"}]}")));
        assertInstanceOf(ImageProvider.SubmitOutcome.Completed.class, provider.submit(withImg, shadowed));
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/images/edits")));
        wm.verify(0, postRequestedFor(urlEqualTo("/v1/images/generations")));

        // edits 端点路径本身可用 path_overrides.editsSubmitPath 覆盖
        wm.resetAll();
        ChannelConfigService.ChannelRuntime customEdits = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl() + "/v1", "k", Map.of("editsSubmitPath", "/img/edits"),
                Map.of("refImageStyle", "uuapi"));
        wm.stubFor(post(urlEqualTo("/v1/img/edits"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"url\":\"https://x/f.png\"}]}")));
        assertInstanceOf(ImageProvider.SubmitOutcome.Completed.class, provider.submit(withImg, customEdits));
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/img/edits")));
    }

    @Test
    void channelDefaultResponseFormatInjectedButExplicitWins() {
        ChannelConfigService.ChannelRuntime urlDefault = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl() + "/v1", "k", Map.of(), Map.of("defaultResponseFormat", "url"));

        // 业务方未指定 → 渠道默认代为注入 response_format=url
        String injected = provider.buildRequestJson(cmd(), urlDefault);
        assertTrue(injected.contains("\"response_format\":\"url\""), injected);

        // 业务方显式指定 → 以业务方为准，渠道默认不覆盖
        ImageProvider.SubmitCommand explicit = new ImageProvider.SubmitCommand("m1", "p", 1,
                null, null, null, null, Map.of("response_format", "b64_json"), 10_000);
        String body = provider.buildRequestJson(explicit, urlDefault);
        assertTrue(body.contains("\"response_format\":\"b64_json\""), body);
        assertFalse(body.contains("\"response_format\":\"url\""), body);

        // 未配默认的渠道 → 完全不注入
        String untouched = provider.buildRequestJson(cmd(), rt);
        assertFalse(untouched.contains("response_format"), untouched);
    }
}
