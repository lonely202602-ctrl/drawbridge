package com.drawbridge.adapter;

import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.http.UpstreamHttp;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        return new ImageProvider.SubmitCommand("gpt-image-2", "cat", 1, null, null, null, null, 10_000);
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
    void missingTaskIdIsUnknownError() {
        wm.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(202).withBody("{\"weird\":true}")));
        try {
            provider.submit(cmd(), rt(Map.of()));
            fail("应抛出异常");
        } catch (UpstreamCallException e) {
            assertEquals(UpstreamErrorKind.UNKNOWN, e.getKind());
        }
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
