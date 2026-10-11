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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
 * 协议族②（OpenAI 风格异步任务）适配器测试：nezhagate/uuapi 两种内置 flavor + 指针覆盖。
 */
class OpenAiAsyncTaskProviderTest {

    private WireMockServer wm;
    private OpenAiAsyncTaskProvider provider;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        provider = new OpenAiAsyncTaskProvider(new UpstreamHttp());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private ChannelConfigService.ChannelRuntime rt(Map<String, Object> extra) {
        return new ChannelConfigService.ChannelRuntime(wm.baseUrl() + "/v1", "sk-x", Map.of(), extra);
    }

    private ImageProvider.SubmitCommand cmd() {
        return new ImageProvider.SubmitCommand("gpt-image-2", "cat", 1, null, null, null, null, null, 10_000);
    }

    @Test
    void nezhaFlavorSubmitAndPoll() {
        ChannelConfigService.ChannelRuntime nezha = rt(Map.of("flavor", "nezha"));
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(202).withBody("{\"id\":\"job_1\",\"status\":\"queued\"}")));
        ImageProvider.SubmitOutcome.Accepted accepted = assertInstanceOf(
                ImageProvider.SubmitOutcome.Accepted.class, provider.submit(cmd(), nezha));
        assertEquals("job_1", accepted.upstreamTaskId());

        wm.stubFor(get(urlEqualTo("/v1/images/jobs/job_1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"processing\"}")));
        assertEquals(ImageProvider.PollOutcome.Phase.RUNNING, provider.poll("job_1", nezha).phase());

        wm.stubFor(get(urlEqualTo("/v1/images/jobs/job_1"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"status":"succeeded","data":[{"url":"https://cdn/a.png"}]}
                        """)));
        ImageProvider.PollOutcome ok = provider.poll("job_1", nezha);
        assertEquals(ImageProvider.PollOutcome.Phase.SUCCEEDED, ok.phase());
        assertEquals("https://cdn/a.png", ok.resultUrls().getFirst());

        wm.stubFor(get(urlEqualTo("/v1/images/jobs/job_1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"status\":\"failed\",\"error\":{\"message\":\"生成失败：审核未通过\"}}")));
        ImageProvider.PollOutcome bad = provider.poll("job_1", nezha);
        assertEquals(ImageProvider.PollOutcome.Phase.FAILED, bad.phase());
        assertEquals("生成失败：审核未通过", bad.failMessage());
    }

    @Test
    void uuFlavorUsesDifferentPathsAndFields() {
        ChannelConfigService.ChannelRuntime uu = rt(Map.of("flavor", "uu"));
        wm.stubFor(post(urlEqualTo("/v1/images/generations/async"))
                .willReturn(aResponse().withStatus(202).withBody("{\"task_id\":\"t_9\"}")));
        ImageProvider.SubmitOutcome.Accepted accepted = assertInstanceOf(
                ImageProvider.SubmitOutcome.Accepted.class, provider.submit(cmd(), uu));
        assertEquals("t_9", accepted.upstreamTaskId());

        wm.stubFor(get(urlEqualTo("/v1/images/tasks/t_9"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"task_id":"t_9","status":"completed","data":[{"url":"https://uu/b.png"}]}
                        """)));
        ImageProvider.PollOutcome ok = provider.poll("t_9", uu);
        assertEquals(ImageProvider.PollOutcome.Phase.SUCCEEDED, ok.phase());
        assertEquals("https://uu/b.png", ok.resultUrls().getFirst());
    }

    @Test
    void pointerOverridesWin() {
        // 上游形态不标准时，渠道级 extra_config 指针覆盖（声明式适配能力）
        ChannelConfigService.ChannelRuntime custom = rt(Map.of(
                "flavor", "nezha",
                "submitPath", "/gen",
                "queryPath", "/gen/{id}",
                "idField", "job_no",
                "statusField", "state",
                "urlField", "output.url",
                "statusMap", Map.of("ok", "SUCCEEDED", "working", "RUNNING", "dead", "FAILED")));
        wm.stubFor(post(urlEqualTo("/v1/gen"))
                .willReturn(aResponse().withStatus(202).withBody("{\"job_no\":\"zz1\"}")));
        assertEquals("zz1", assertInstanceOf(ImageProvider.SubmitOutcome.Accepted.class,
                provider.submit(cmd(), custom)).upstreamTaskId());

        wm.stubFor(get(urlEqualTo("/v1/gen/zz1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"state\":\"ok\",\"output\":{\"url\":\"https://c/x.png\"}}")));
        ImageProvider.PollOutcome ok = provider.poll("zz1", custom);
        assertEquals(ImageProvider.PollOutcome.Phase.SUCCEEDED, ok.phase());
        assertEquals("https://c/x.png", ok.resultUrls().getFirst());
    }

    @Test
    void missingTaskIdIsBadResponseNotRedirectable() {
        // 2xx 但没任务号：上游可能已受理（代理回垃圾）→ BAD_RESPONSE，不换渠道重发（防重复扣费）
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(202).withBody("{\"weird\":true}")));
        try {
            provider.submit(cmd(), rt(Map.of()));
            fail("应抛出异常");
        } catch (UpstreamCallException e) {
            assertEquals(UpstreamErrorKind.BAD_RESPONSE, e.getKind());
            assertFalse(e.getKind().isRetryable(), "BAD_RESPONSE 不可转移重发");
        }
    }

    @Test
    void uuapiEditsAsyncRoutesReferenceImagesWithTaskIdAccepted() {
        // UUAPI 异步图生图：带参考图自动切 /images/edits/async，参考图包成 images[].image_url
        ChannelConfigService.ChannelRuntime uu = rt(Map.of("flavor", "uu", "refImageStyle", "uuapi"));
        ImageProvider.SubmitCommand withImg = new ImageProvider.SubmitCommand("gpt-image-2.5", "美化", 1,
                null, null, null, List.of("https://r1.png"), null, 10_000);
        wm.stubFor(post(urlEqualTo("/v1/images/edits/async"))
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"task_id\":\"imgtask_1\",\"status\":\"pending\"}")));
        ImageProvider.SubmitOutcome.Accepted accepted = assertInstanceOf(
                ImageProvider.SubmitOutcome.Accepted.class, provider.submit(withImg, uu));
        assertEquals("imgtask_1", accepted.upstreamTaskId());

        // 轮询：completed 时结果嵌在 result.data[*].url
        wm.stubFor(get(urlEqualTo("/v1/images/tasks/imgtask_1"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"status":"completed","result":{"data":[{"url":"https://cdn.out/final.png"}]}}
                        """)));
        ChannelConfigService.ChannelRuntime uuUrl = rt(Map.of("flavor", "uu",
                "refImageStyle", "uuapi", "urlField", "result.data[*].url"));
        ImageProvider.PollOutcome poll = provider.poll("imgtask_1", uuUrl);
        assertEquals(ImageProvider.PollOutcome.Phase.SUCCEEDED, poll.phase());
        assertEquals("https://cdn.out/final.png", poll.resultUrls().getFirst());
    }

    @Test
    void editsSwitchWinsOverSubmitPathOverride() {
        // 回归：path_overrides.submitPath 只管文生图；带参考图的 uuapi 渠道仍必须切 edits/async
        ChannelConfigService.ChannelRuntime shadowed = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl() + "/v1", "sk-x", Map.of("submitPath", "/images/generations/async"),
                Map.of("flavor", "uu", "refImageStyle", "uuapi"));
        ImageProvider.SubmitCommand withImg = new ImageProvider.SubmitCommand("gpt-image-2.5", "美化", 1,
                null, null, null, List.of("https://r1.png"), null, 10_000);
        wm.stubFor(post(urlEqualTo("/v1/images/edits/async"))
                .willReturn(aResponse().withStatus(202).withBody("{\"task_id\":\"imgtask_9\"}")));
        assertEquals("imgtask_9", assertInstanceOf(ImageProvider.SubmitOutcome.Accepted.class,
                provider.submit(withImg, shadowed)).upstreamTaskId());
        String body = wm.findAll(postRequestedFor(urlEqualTo("/v1/images/edits/async")))
                .get(0).getBodyAsString();
        assertTrue(body.contains("\"image_url\":\"https://r1.png\""), body);

        // edits 端点路径可用 path_overrides.editsSubmitPath 覆盖
        wm.resetAll();
        ChannelConfigService.ChannelRuntime customEdits = new ChannelConfigService.ChannelRuntime(
                wm.baseUrl() + "/v1", "sk-x", Map.of("editsSubmitPath", "/img/edits"),
                Map.of("flavor", "uu", "refImageStyle", "uuapi"));
        wm.stubFor(post(urlEqualTo("/v1/img/edits"))
                .willReturn(aResponse().withStatus(202).withBody("{\"task_id\":\"imgtask_10\"}")));
        assertEquals("imgtask_10", assertInstanceOf(ImageProvider.SubmitOutcome.Accepted.class,
                provider.submit(withImg, customEdits)).upstreamTaskId());
    }

    @Test
    void syncShapeResponseCompletedInsteadOfDiscarded() {
        // 渠道配错的真实场景：异步适配器打到了同步生图端点，HTTP 200 直接回图片、无任务号
        ChannelConfigService.ChannelRuntime nezha = rt(Map.of("flavor", "nezha"));

        // b64 形状 → 收下，转 data URI
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}")));
        ImageProvider.SubmitOutcome.Completed b64 = assertInstanceOf(
                ImageProvider.SubmitOutcome.Completed.class, provider.submit(cmd(), nezha));
        assertEquals(List.of("data:image/png;base64,aGVsbG8="), b64.resultUrls());
        assertTrue(b64.inlineB64());

        // url 形状 → 同样收下
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"url\":\"https://x/a.png\"}]}")));
        ImageProvider.SubmitOutcome.Completed url = assertInstanceOf(
                ImageProvider.SubmitOutcome.Completed.class, provider.submit(cmd(), nezha));
        assertEquals(List.of("https://x/a.png"), url.resultUrls());

        // 有任务号的正常异步响应不受影响
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(202).withBody("{\"id\":\"job_9\"}")));
        ImageProvider.SubmitOutcome.Accepted accepted = assertInstanceOf(
                ImageProvider.SubmitOutcome.Accepted.class, provider.submit(cmd(), nezha));
        assertEquals("job_9", accepted.upstreamTaskId());
    }

    @Test
    void pollHttpErrorPropagatesClassified() {
        wm.stubFor(get(urlEqualTo("/v1/images/jobs/j1"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}")));
        try {
            provider.poll("j1", rt(Map.of()));
            fail("应抛出异常");
        } catch (UpstreamCallException e) {
            assertEquals(UpstreamErrorKind.UPSTREAM_SERVER_ERROR, e.getKind());
        }
    }
}
