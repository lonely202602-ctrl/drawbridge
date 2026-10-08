package com.drawbridge.adapter;

import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.http.UpstreamHttp;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 协议族④（Crun 私有任务协议）适配器测试：CreateTask/TaskInfo、{code,message,data} 壳、
 * x-api-key 鉴权、resolution 映射、model_variant 仅 gpt-image-2-5 生效。
 */
class CrunTaskProviderTest {

    private WireMockServer wm;
    private CrunTaskProvider provider;
    private ChannelConfigService.ChannelRuntime rt;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        provider = new CrunTaskProvider(new UpstreamHttp());
        rt = new ChannelConfigService.ChannelRuntime(wm.baseUrl(), "crun-key", Map.of(), Map.of());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private ImageProvider.SubmitCommand cmd(String model) {
        return new ImageProvider.SubmitCommand(model, "a cat", 1, null, null, null, null, 10_000);
    }

    @Test
    void submitReturnsTaskId() {
        wm.stubFor(post(urlEqualTo("/client/job/CreateTask"))
                .withHeader("x-api-key", containing("crun-key"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"code\":200,\"message\":\"ok\",\"data\":{\"task_id\":\"ct_1\"}}")));
        ImageProvider.SubmitOutcome.Accepted accepted = assertInstanceOf(
                ImageProvider.SubmitOutcome.Accepted.class, provider.submit(cmd("openai/gpt-image-2-5"), rt));
        assertEquals("ct_1", accepted.upstreamTaskId());
    }

    @Test
    void submitErrorsClassified() {
        // 余额不足（业务 code 402）
        wm.stubFor(post(urlEqualTo("/client/job/CreateTask"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"code\":402,\"message\":\"余额不足\"}")));
        expectKind(cmd("m"), rt, UpstreamErrorKind.BALANCE_EXHAUSTED);

        // 鉴权失败（HTTP 401）
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/client/job/CreateTask"))
                .willReturn(aResponse().withStatus(401).withBody("{\"code\":401,\"message\":\"bad key\"}")));
        expectKind(cmd("m"), rt, UpstreamErrorKind.AUTH_FAILED);

        // 内容问题
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/client/job/CreateTask"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"code\":501,\"message\":\"生成失败：内容审核未通过\"}")));
        expectKind(cmd("m"), rt, UpstreamErrorKind.CONTENT_REJECTED);
    }

    private void expectKind(ImageProvider.SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt,
                            UpstreamErrorKind expected) {
        try {
            provider.submit(cmd, rt);
            fail("应抛出 UpstreamCallException");
        } catch (UpstreamCallException e) {
            assertEquals(expected, e.getKind());
        }
    }

    @Test
    void pollLifecycle() {
        // pending
        wm.stubFor(get(urlEqualTo("/client/job/TaskInfo?task_id=ct_1"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"code\":200,\"data\":{\"status\":\"pending\"}}")));
        assertEquals(ImageProvider.PollOutcome.Phase.RUNNING, provider.poll("ct_1", rt).phase());

        // success + credits 成本
        wm.stubFor(get(urlEqualTo("/client/job/TaskInfo?task_id=ct_1"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"code":200,"data":{"status":"success","credits":12,
                         "result":{"media_urls":["https://files.crun/a.png","https://files.crun/b.png"]}}}
                        """)));
        ImageProvider.PollOutcome ok = provider.poll("ct_1", rt);
        assertEquals(ImageProvider.PollOutcome.Phase.SUCCEEDED, ok.phase());
        assertEquals(2, ok.resultUrls().size());
        assertEquals(new BigDecimal(12), ok.cost());

        // failed + 原因
        wm.stubFor(get(urlEqualTo("/client/job/TaskInfo?task_id=ct_1"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"code":200,"data":{"status":"failed","result":{"message":"提示词不合规"}}}
                        """)));
        ImageProvider.PollOutcome bad = provider.poll("ct_1", rt);
        assertEquals(ImageProvider.PollOutcome.Phase.FAILED, bad.phase());
        assertEquals("提示词不合规", bad.failMessage());
    }

    @Test
    void requestShapeMatchesCrunContract() {
        // gpt-image-2-5 家族带 model_variant（默认 flare）+ img_urls + resolution
        ImageProvider.SubmitCommand withImage = new ImageProvider.SubmitCommand(
                "openai/gpt-image-2-5", "p", 1, "2048x1024", "3:2",
                List.of("https://pub/ref.png"), null, 10_000);
        String body = provider.buildRequestJson(withImage, rt);
        assertTrue(body.contains("\"model\":\"openai/gpt-image-2-5\""));
        assertTrue(body.contains("\"model_variant\":\"flare\""));
        assertTrue(body.contains("\"img_urls\":[\"https://pub/ref.png\"]"));
        assertTrue(body.contains("\"aspect_ratio\":\"3:2\""));
        assertTrue(body.contains("\"resolution\":\"2k\""));

        // 非 gpt-image-2-5 家族不带 model_variant；小尺寸 → 1k
        String body2 = provider.buildRequestJson(cmd("google/nano-banana-3"), rt);
        assertTrue(body2.contains("\"model\":\"google/nano-banana-3\""));
        assertTrue(!body2.contains("model_variant"));
        assertTrue(!body2.contains("resolution"));

        // modelVariant option 覆盖
        ImageProvider.SubmitCommand variant = new ImageProvider.SubmitCommand(
                "openai/gpt-image-2-5", "p", 1, null, null, null,
                Map.of("modelVariant", "sunburst"), 10_000);
        assertTrue(provider.buildRequestJson(variant, rt).contains("\"model_variant\":\"sunburst\""));
    }
}
