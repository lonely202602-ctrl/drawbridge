package com.drawbridge.integration;

import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.entity.GroupMember;
import com.drawbridge.domain.entity.ModelGroup;
import com.drawbridge.domain.entity.TaskAttempt;
import com.drawbridge.domain.entity.UsageLog;
import com.drawbridge.domain.mapper.ChannelMapper;
import com.drawbridge.domain.entity.ApiToken;
import com.drawbridge.domain.mapper.ApiTokenMapper;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.domain.mapper.GroupMemberMapper;
import com.drawbridge.domain.mapper.ModelGroupMapper;
import com.drawbridge.domain.mapper.TaskAttemptMapper;
import com.drawbridge.domain.mapper.UsageLogMapper;
import com.drawbridge.health.ChannelOpsService;
import com.drawbridge.infrastructure.AesGcmCipher;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import jakarta.annotation.Resource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全链路集成测试（真实 MySQL + WireMock 假上游）：
 * 提交 → 执行器路由/重试/停用 → 轮询推进 → 终态结算/退款 → 查询/任务详情/管理端运营闭环。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GatewayIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    static final WireMockServer WM = new WireMockServer(options().dynamicPort());

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Resource
    ChannelConfigService configService;
    @Resource
    SystemConfigService config;
    @Resource
    AesGcmCipher cipher;
    @Resource
    ChannelOpsService channelOps;
    @Autowired
    ChannelMapper channelMapper;
    @Autowired
    ModelGroupMapper groupMapper;
    @Autowired
    GroupMemberMapper memberMapper;
    @Autowired
    ApiTokenMapper tokenMapper;
    @Autowired
    GenerateTaskMapper taskMapper;
    @Autowired
    TaskAttemptMapper attemptMapper;
    @Autowired
    UsageLogMapper usageLogMapper;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.sql.init.mode", () -> "always");
        r.add("spring.sql.init.schema-locations", () -> "file:../db/schema.sql");
    }

    @BeforeAll
    static void beforeAll(@Autowired com.drawbridge.admin.AdminAuthService adminAuth,
                          @Autowired SystemConfigService cfg) {
        WM.start();
        adminAuth.seedDefaultAdmin();
        // 测试加速：轮询间隔拉短；其余保持生产默认
        cfg.updateAll(Map.of(
                ConfigKeys.POLL_INITIAL_INTERVAL_MS.getKey(), "100",
                ConfigKeys.POLL_MAX_INTERVAL_MS.getKey(), "300"));
    }

    @AfterAll
    static void afterAll() {
        WM.stop();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM task_attempt");
        jdbc.update("DELETE FROM usage_log");
        jdbc.update("DELETE FROM generate_task");
        jdbc.update("DELETE FROM group_member");
        jdbc.update("DELETE FROM model_group");
        jdbc.update("DELETE FROM upstream_model");
        jdbc.update("DELETE FROM api_token");
        jdbc.update("DELETE FROM channel");
        configService.refresh();
        WM.resetAll();
    }

    // ------------------------------------------------------------------ 种子工具

    private Channel seedChannel(String name, String protocol, String baseUrl, String apiKey) {
        return seedChannel(name, protocol, baseUrl, apiKey, null);
    }

    private Channel seedChannel(String name, String protocol, String baseUrl, String apiKey,
                                java.util.Map<String, Object> extra) {
        Channel c = new Channel();
        c.setName(name);
        c.setProtocol(protocol);
        c.setBaseUrl(baseUrl);
        c.setApiKeyEnc(cipher.encrypt(apiKey));
        if (extra != null && !extra.isEmpty()) {
            c.setExtraConfig(com.drawbridge.common.JsonUtil.toJson(extra));
        }
        c.setEnabled(1);
        c.setStatus(1);
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        channelMapper.insert(c);
        return c;
    }

    private ModelGroup seedGroup(String code, String price, List<Channel> members, List<String> models) {
        ModelGroup g = new ModelGroup();
        g.setGroupCode(code);
        g.setDisplayName(code);
        g.setStrategy("FAILOVER");
        g.setBizPrice(new BigDecimal(price));
        g.setEnabled(1);
        LocalDateTime now = LocalDateTime.now();
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        groupMapper.insert(g);
        for (int i = 0; i < members.size(); i++) {
            GroupMember m = new GroupMember();
            m.setGroupId(g.getId());
            m.setChannelId(members.get(i).getId());
            m.setUpstreamModel(models.get(i));
            m.setWeight(1);
            m.setSortOrder(i);
            m.setEnabled(1);
            m.setCreatedAt(now);
            m.setUpdatedAt(now);
            memberMapper.insert(m);
        }
        return g;
    }

    private String seedToken(long appId, String sk, String quota, List<String> allowedGroups) {
        ApiToken t = new ApiToken();
        t.setName("测试令牌");
        LocalDateTime now = LocalDateTime.now();
        t.setSkHash(com.drawbridge.common.Ids.sha256Hex(sk));
        t.setSkPrefix(sk.substring(0, 10));
        t.setStatus(1);
        t.setAllowedGroups(allowedGroups == null ? null
                : com.drawbridge.common.JsonUtil.toJson(allowedGroups));
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        tokenMapper.insert(t);
        return sk;
    }

    private void refreshSnapshot() {
        configService.refresh();
    }

    /** 提交生图任务并断言业务响应。返回 data 节点 JSON 字符串。 */
    private String submit(String sk, String body) throws Exception {
        MvcResult result = mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private String field(String responseJson, String field) {
        com.fasterxml.jackson.databind.JsonNode node =
                com.drawbridge.common.JsonUtil.readTree(responseJson).path("data");
        for (String seg : field.split("\\.")) {
            node = node.path(seg);
        }
        return node.asText();
    }

    /**
     * 统一异步契约下的完整调用姿势：提交立即返回非终态任务号 → 轮询查询接口到终态，
     * 返回最终查询响应。任何协议族（同步/异步上游）都走这一条路。
     */
    private String submitAndAwait(String sk, String body) throws Exception {
        String posted = submit(sk, body);
        String taskId = field(posted, "taskId");
        assertTrue(List.of("SUBMITTED", "RUNNING").contains(field(posted, "status")),
                "提交应立即返回非终态（不等上游）: " + field(posted, "status"));
        awaitTerminal(taskId);
        return mvc.perform(get("/v1/images/tasks/" + taskId).header("Authorization", "Bearer " + sk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
    }

    private GenerateTask awaitTerminal(String taskId) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            GenerateTask t = taskMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<GenerateTask>lambdaQuery()
                            .eq(GenerateTask::getTaskId, taskId));
            assertNotNull(t);
            assertTrue(t.getStatus().equals("SUCCEEDED") || t.getStatus().equals("FAILED"),
                    "仍在中间态: " + t.getStatus());
        });
        return taskMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<GenerateTask>lambdaQuery()
                .eq(GenerateTask::getTaskId, taskId));
    }

    private String adminToken() throws Exception {
        return com.drawbridge.common.JsonUtil.readTree(mvc.perform(
                        post("/admin/login")
                                .header("Content-Type", "application/json")
                                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
    }

    // ------------------------------------------------------------------ 业务链路

    @org.junit.jupiter.api.DisplayName("同步上游：提交秒回任务号，虚拟线程后台出图后查询可得")
    @Test
    void syncUpstreamInlineSuccess() throws Exception {
        Channel ch = seedChannel("同步上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("image2.5", "0.02", List.of(ch), List.of("gpt-image-2.5"));
        String sk = seedToken(1L, "sk-sync-test-aaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/1.png\"}]}")));

        String resp = submitAndAwait(sk, "{\"model\":\"image2.5\",\"prompt\":\"一只猫\"}");

        assertEquals("SUCCEEDED", field(resp, "status"));
        assertEquals("https://cdn.result/1.png",
                com.drawbridge.common.JsonUtil.readTree(resp)
                        .path("data").path("result").path("urls").get(0).asText());

        GenerateTask task = taskMapper.selectList(null).getFirst();
        assertEquals("SUCCEEDED", task.getStatus());
        assertEquals(WM.baseUrl() + "/v1/images/generations", task.getUpstreamUrl());
        assertNotNull(task.getUpstreamRequest());          // 封装请求体已存档
        // 用量流水（等其落地）
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(1, usageLogMapper.selectCount(null)));
    }

    @org.junit.jupiter.api.DisplayName("异步上游：提交秒回 → 轮询推进 → 成功结算")
    @Test
    void asyncUpstreamFullChain() throws Exception {
        Channel ch = seedChannel("异步上游", "OPENAI_ASYNC_TASK", WM.baseUrl() + "/v1",
                "sk-up", Map.of("flavor", "nezha"));
        seedGroup("image2.5", "0.02", List.of(ch), List.of("gpt-image-2"));
        String sk = seedToken(1L, "sk-async-test-aaaa", "10.00", null);
        refreshSnapshot();

        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(202).withBody("{\"id\":\"job_1\"}")));
        WM.stubFor(WireMock.get(urlEqualTo("/v1/images/jobs/job_1"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"status\":\"processing\"}")));

        String resp = submit(sk, "{\"model\":\"image2.5\",\"prompt\":\"一只狗\"}");
        String taskId = field(resp, "taskId");
        assertTrue(List.of("SUBMITTED", "RUNNING").contains(field(resp, "status")),
                "提交应立即返回非终态（不等上游）: " + field(resp, "status"));

        // 轮询第二次查到成功
        WM.stubFor(WireMock.get(urlEqualTo("/v1/images/jobs/job_1"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"status\":\"succeeded\",\"data\":[{\"url\":\"https://cdn.result/2.png\"}]}")));

        GenerateTask task = awaitTerminal(taskId);
        assertEquals("SUCCEEDED", task.getStatus());
        assertEquals("job_1", task.getUpstreamTaskId());
        assertEquals(ch.getId(), task.getChannelId());
        assertEquals(WM.baseUrl() + "/v1/images/generations", task.getUpstreamUrl());
        // 任务查询接口
        mvc.perform(get("/v1/images/tasks/" + taskId).header("Authorization", "Bearer " + sk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.urls[0]").value("https://cdn.result/2.png"));
    }

    @org.junit.jupiter.api.DisplayName("故障转移：A 渠道 5xx → B 渠道接手成功，重试时间线完整")
    @Test
    void failoverOnServerError() throws Exception {
        Channel a = seedChannel("渠道A", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-a");
        Channel b = seedChannel("渠道B", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-b");
        seedGroup("image2.5", "0.01", List.of(a, b), List.of("m", "m"));
        String sk = seedToken(1L, "sk-failover-aaaaa", "10.00", null);
        refreshSnapshot();

        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-a"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}")));
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-b"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/b.png\"}]}")));

        String resp = submitAndAwait(sk, "{\"model\":\"image2.5\",\"prompt\":\"x\"}");
        assertEquals("SUCCEEDED", field(resp, "status"));
        GenerateTask task = taskMapper.selectList(null).getFirst();
        assertEquals(b.getId(), task.getChannelId());

        // 重试时间线：2 条尝试（A 失败 5xx + B 成功）
        List<TaskAttempt> attempts = attemptMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TaskAttempt>lambdaQuery()
                        .eq(TaskAttempt::getTaskId, task.getId())
                        .orderByAsc(TaskAttempt::getAttemptNo));
        assertEquals(2, attempts.size());
        assertEquals("FAILED", attempts.get(0).getOutcome());
        assertEquals("UPSTREAM_SERVER_ERROR", attempts.get(0).getErrorKind());
        assertEquals(a.getId(), attempts.get(0).getChannelId());
        assertEquals("SUCCESS", attempts.get(1).getOutcome());
        assertEquals(b.getId(), attempts.get(1).getChannelId());

        // 管理端任务详情能看到同一条时间线
        String admin = adminToken();
        mvc.perform(get("/admin/tasks/detail/" + task.getId()).header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attempts.length()").value(2))
                .andExpect(jsonPath("$.data.attempts[0].channelName").value("渠道A"))
                .andExpect(jsonPath("$.data.attempts[1].channelName").value("渠道B"));
    }

    @org.junit.jupiter.api.DisplayName("上游没米：自动停用 + 告警 + 换渠道成功；一键恢复")
    @Test
    void balanceExhaustedAutoDisablesChannel() throws Exception {
        Channel a = seedChannel("没米渠道", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-a");
        Channel b = seedChannel("有米渠道", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-b");
        seedGroup("image2.5", "0.01", List.of(a, b), List.of("m", "m"));
        String sk = seedToken(1L, "sk-balance-aaaaaa", "10.00", null);
        refreshSnapshot();

        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-a"))
                .willReturn(aResponse().withStatus(403).withBody(
                        "{\"error\":{\"message\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}")));
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-b"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/ok.png\"}]}")));

        String resp = submitAndAwait(sk, "{\"model\":\"image2.5\",\"prompt\":\"x\"}");
        assertEquals("SUCCEEDED", field(resp, "status"));

        // 渠道 A 被自动停用（status=4），恢复接口把它拉回来
        assertEquals(4, channelMapper.selectById(a.getId()).getStatus());
        String admin = adminToken();
        mvc.perform(post("/admin/channels/" + a.getId() + "/enable").header("X-Admin-Token", admin))
                .andExpect(status().isOk());
        assertEquals(1, channelMapper.selectById(a.getId()).getStatus());
    }

    @org.junit.jupiter.api.DisplayName("提示词/内容类错误：不重试，作为任务结果返回 + 退款")
    @Test
    void contentRejectedIsTerminalAndRefunded() throws Exception {
        Channel a = seedChannel("审核渠道", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-a");
        Channel b = seedChannel("备胎渠道", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-b");
        seedGroup("image2.5", "0.01", List.of(a, b), List.of("m", "m"));
        String sk = seedToken(1L, "sk-content-aaaaaa", "10.00", null);
        refreshSnapshot();

        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(400).withBody(
                        "{\"error\":{\"message\":\"content_policy_violation\"}}")));

        String resp = submitAndAwait(sk, "{\"model\":\"image2.5\",\"prompt\":\"违规内容\"}");
        assertEquals("FAILED", field(resp, "status"));
        assertEquals("CONTENT_REJECTED", field(resp, "error.code"));

        GenerateTask task = taskMapper.selectList(null).getFirst();
        // 只尝试了 1 个渠道（内容问题换渠道也不会好）
        List<TaskAttempt> attempts = attemptMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TaskAttempt>lambdaQuery()
                        .eq(TaskAttempt::getTaskId, task.getId()));
        assertEquals(1, attempts.size());
        // 失败也留用量流水（成功=0）
        assertEquals(1, usageLogMapper.selectList(null).stream()
                .filter(u -> u.getSuccess() == 0).count());
    }

    @org.junit.jupiter.api.DisplayName("全部渠道失败：UPSTREAM_ALL_FAILED + 退款")
    @Test
    void allChannelsFailedRefunds() throws Exception {
        Channel a = seedChannel("A", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-a");
        Channel b = seedChannel("B", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-b");
        seedGroup("image2.5", "0.01", List.of(a, b), List.of("m", "m"));
        String sk = seedToken(1L, "sk-allfail-aaaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"down\"}}")));

        String resp = submitAndAwait(sk, "{\"model\":\"image2.5\",\"prompt\":\"x\"}");
        assertEquals("FAILED", field(resp, "status"));
        assertEquals("UPSTREAM_ALL_FAILED", field(resp, "error.code"));

        GenerateTask task = taskMapper.selectList(null).getFirst();
        assertEquals(2, task.getAttemptCount());
        assertEquals(1, usageLogMapper.selectCount(null));
    }

    @org.junit.jupiter.api.DisplayName("异步任务在上游侧失败：轮询发现 → 任务失败 + 退款（不重发）")
    @Test
    void asyncUpstreamTaskFailed() throws Exception {
        Channel ch = seedChannel("异步", "OPENAI_ASYNC_TASK", WM.baseUrl() + "/v1",
                "sk-up", Map.of("flavor", "nezha"));
        seedGroup("image2.5", "0.02", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-asyncfail-aaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(202).withBody("{\"id\":\"j9\"}")));
        WM.stubFor(WireMock.get(urlEqualTo("/v1/images/jobs/j9"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"status\":\"failed\",\"error\":{\"message\":\"渲染超时\"}}")));

        String taskId = field(submit(sk, "{\"model\":\"image2.5\",\"prompt\":\"x\"}"), "taskId");
        GenerateTask task = awaitTerminal(taskId);
        assertEquals("FAILED", task.getStatus());
        assertEquals("UPSTREAM_TASK_FAILED", task.getErrorCode());
        assertEquals("渲染超时", task.getErrorMsg());
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(1, usageLogMapper.selectCount(null)));
    }

    @org.junit.jupiter.api.DisplayName("Crun 全链路：CreateTask → TaskInfo → 成功，credits 记成本")
    @Test
    void crunFullChain() throws Exception {
        Channel ch = seedChannel("Crun", "CRUN_TASK", WM.baseUrl(), "crun-key");
        seedGroup("crun-image", "0.05", List.of(ch), List.of("openai/gpt-image-2-5"));
        String sk = seedToken(1L, "sk-crun-aaaaaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/client/job/CreateTask"))
                .withHeader("x-api-key", containing("crun-key"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"code\":200,\"message\":\"ok\",\"data\":{\"task_id\":\"ct_9\"}}")));
        WM.stubFor(WireMock.get(urlEqualTo("/client/job/TaskInfo?task_id=ct_9"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"code\":200,\"data\":{\"status\":\"pending\"}}")));

        String resp = submit(sk, "{\"model\":\"crun-image\",\"prompt\":\"x\"}");
        String taskId = field(resp, "taskId");
        assertTrue(List.of("SUBMITTED", "RUNNING").contains(field(resp, "status")),
                "提交应立即返回非终态（不等上游）: " + field(resp, "status"));

        WM.stubFor(WireMock.get(urlEqualTo("/client/job/TaskInfo?task_id=ct_9"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"code\":200,\"data\":{\"status\":\"success\",\"credits\":12,"
                                + "\"result\":{\"media_urls\":[\"https://files.crun/x.png\"]}}}")));

        GenerateTask task = awaitTerminal(taskId);
        assertEquals("SUCCEEDED", task.getStatus());
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(1, usageLogMapper.selectCount(null)));
        assertEquals(1, usageLogMapper.selectList(null).getFirst().getSuccess());
    }

    // ------------------------------------------------------------------ 入口校验

    @org.junit.jupiter.api.DisplayName("入口：鉴权/授权/额度/幂等")
    @Test
    void entryGuards() throws Exception {
        Channel ch = seedChannel("同步", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("image2.5", "0.02", List.of(ch), List.of("m"));
        seedGroup("vip.model", "0.10", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-guard-aaaaaaa", null, List.of("image2.5"));
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://c/1.png\"}]}")));

        // 无令牌 / 坏令牌
        mvc.perform(post("/v1/images/tasks").header("Content-Type", "application/json")
                        .content("{\"model\":\"image2.5\",\"prompt\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer sk-wrong")
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"image2.5\",\"prompt\":\"x\"}"))
                .andExpect(status().isUnauthorized());

        // 未授权分组
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"vip.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isForbidden());

        // 模型不存在
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"nope\",\"prompt\":\"x\"}"))
                .andExpect(status().isBadRequest());

        // 参数校验：prompt 和 image 都没有
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"image2.5\"}"))
                .andExpect(status().isBadRequest());

        // 幂等：同 key 两次 → 同一任务号；不同 key/无 key → 不同任务
        String r1 = submit(sk, "{\"model\":\"image2.5\",\"prompt\":\"x\"}");
        String id1 = field(r1, "taskId");
        MvcResult r2 = mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .header("X-Idempotency-Key", "same-key")
                        .content("{\"model\":\"image2.5\",\"prompt\":\"x\"}"))
                .andExpect(status().isOk()).andReturn();
        String id2 = field(r2.getResponse().getContentAsString(), "taskId");
        MvcResult r3 = mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .header("X-Idempotency-Key", "same-key")
                        .content("{\"model\":\"image2.5\",\"prompt\":\"x\"}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(field(r2.getResponse().getContentAsString(), "taskId"),
                field(r3.getResponse().getContentAsString(), "taskId"));
        // 无 key 的 r1 与带 key 的 r2 是两个独立任务
        var id3 = field(r3.getResponse().getContentAsString(), "taskId");
        assertTrue(!id1.equals(id3));

        // 模型表
        mvc.perform(get("/v1/images/models").header("Authorization", "Bearer " + sk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].model").value("image2.5"));
    }

    // ------------------------------------------------------------------ 管理端运营闭环

    @org.junit.jupiter.api.DisplayName("查询限流：超过 openapi.query_rate_per_min 后 GET 返回 429，与提交分开计数")
    @Test
    void queryRateLimitedSeparatelyFromSubmit() throws Exception {
        Channel ch = seedChannel("限流上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("qrate.model", "0.02", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-qrate-aaaaaaa", "10.00", null);
        config.updateAll(Map.of(ConfigKeys.OPENAPI_QUERY_RATE_PER_MIN.getKey(), "3"));
        refreshSnapshot();
        try {
            WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                    .willReturn(aResponse().withStatus(200)
                            .withBody("{\"data\":[{\"url\":\"https://c/q.png\"}]}")));
            String taskId = field(submitAndAwait(sk, "{\"model\":\"qrate.model\",\"prompt\":\"x\"}"), "taskId");

            // 提交占提交配额，查询占查询配额：submitAndAwait 已耗 1 次查询，再 2 次到顶
            mvc.perform(get("/v1/images/tasks/" + taskId).header("Authorization", "Bearer " + sk))
                    .andExpect(status().isOk());
            mvc.perform(get("/v1/images/tasks/" + taskId).header("Authorization", "Bearer " + sk))
                    .andExpect(status().isOk());
            mvc.perform(get("/v1/images/tasks/" + taskId).header("Authorization", "Bearer " + sk))
                    .andExpect(status().isTooManyRequests());
        } finally {
            config.updateAll(Map.of(ConfigKeys.OPENAPI_QUERY_RATE_PER_MIN.getKey(), "600"));
            refreshSnapshot();
        }
    }

    @org.junit.jupiter.api.DisplayName("管理端全流程：登录→建渠道→试跑→建分组→发令牌→设置热更新→任务列表")
    @Test
    void adminFullFlow() throws Exception {
        String admin = adminToken();

        // 未登录拦截
        mvc.perform(get("/admin/channels")).andExpect(status().isUnauthorized());

        // 建渠道
        String channelBody = """
                {"name":"wire上游","protocol":"OPENAI_IMAGES_SYNC","baseUrl":"%s/v1",
                 "apiKey":"sk-admin-created","maxConcurrency":3,"costPrice":0.005}
                """.formatted(WM.baseUrl());
        String channelId = com.drawbridge.common.JsonUtil.readTree(mvc.perform(
                        post("/admin/channels").header("X-Admin-Token", admin)
                                .header("Content-Type", "application/json").content(channelBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").asText();

        // 试跑（WireMock 给 /models 通）
        WM.stubFor(WireMock.get(urlEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}")));
        mvc.perform(post("/admin/channels/" + channelId + "/test-run").header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("连通正常，Key 有效"));

        // 同步模型
        mvc.perform(post("/admin/channels/" + channelId + "/sync-models").header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2));

        // 密钥不回显
        mvc.perform(get("/admin/channels").header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].apiKeyEnc").doesNotExist());

        // 建分组 + 挂成员
        String groupId = com.drawbridge.common.JsonUtil.readTree(mvc.perform(
                        post("/admin/groups").header("X-Admin-Token", admin)
                                .header("Content-Type", "application/json")
                                .content("{\"groupCode\":\"image2.5\",\"displayName\":\"Image 2.5\","
                                        + "\"strategy\":\"FAILOVER\",\"bizPrice\":0.02}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").asText();
        mvc.perform(post("/admin/groups/" + groupId + "/members").header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"channelId\":" + channelId + ",\"upstreamModel\":\"m1\",\"sortOrder\":0}"))
                .andExpect(status().isOk());

        // 发令牌（SK 只显一次）
        String tokenResp = mvc.perform(post("/admin/tokens")
                        .header("X-Admin-Token", admin).header("Content-Type", "application/json")
                        .content("{\"name\":\"默认令牌\",\"allowedGroups\":[]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sk = com.drawbridge.common.JsonUtil.readTree(tokenResp).path("data").path("sk").asText();
        assertTrue(sk.startsWith("sk-"));

        // 用刚发的令牌真实走一单（管理端配置 → 业务方调用 打通）
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/admin-flow.png\"}]}")));
        String resp = submitAndAwait(sk, "{\"model\":\"image2.5\",\"prompt\":\"x\"}");
        assertEquals("SUCCEEDED", field(resp, "status"));

        // 任务列表
        mvc.perform(get("/admin/tasks").header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        // 仪表盘
        mvc.perform(get("/admin/dashboard").header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayTotal").value(1))
                .andExpect(jsonPath("$.data.channels.length()").value(1));

        // 设置热更新生效（改并发上限→GET 可见）
        mvc.perform(put("/admin/settings").header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"route.max_channel_attempts\":\"2\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/settings").header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.values['route.max_channel_attempts']").value("2"))
                .andExpect(jsonPath("$.data.descriptions['route.max_channel_attempts']").value("单请求最多尝试的渠道数"));
    }

    @org.junit.jupiter.api.DisplayName("分组编码改名：新编码可调用 / 重名拒绝 / 令牌 allowed_groups 级联同步")
    @Test
    void groupRenameCascadesTokenAllowedGroups() throws Exception {
        String admin = adminToken();
        Channel ch = seedChannel("改名上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        ModelGroup g = seedGroup("old.model", "0.02", List.of(ch), List.of("m"));
        // 限了分组的令牌（allowed_groups 含旧编码）
        String sk = seedToken(1L, "sk-rename-aaaaaa", "10.00", List.of("old.model"));
        refreshSnapshot();

        // ① 改成已存在的编码 → 拒绝
        seedGroup("taken.model", "0.02", List.of(ch), List.of("m"));
        mvc.perform(put("/admin/groups/" + g.getId()).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"groupCode\":\"taken.model\",\"strategy\":\"FAILOVER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("分组编码已存在: taken.model"));

        // ② 正常改名 → 成功，且令牌 allowed_groups 自动同步
        mvc.perform(put("/admin/groups/" + g.getId()).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"groupCode\":\"new.model\",\"strategy\":\"FAILOVER\"}"))
                .andExpect(status().isOk());
        refreshSnapshot();
        ApiToken token = tokenMapper.selectById(
                tokenMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<ApiToken>lambdaQuery()
                        .eq(ApiToken::getSkHash, com.drawbridge.common.Ids.sha256Hex(sk))).get(0).getId());
        assertTrue(token.getAllowedGroups().contains("new.model")
                && !token.getAllowedGroups().contains("old.model"),
                "allowed_groups 未级联: " + token.getAllowedGroups());

        // ③ 新编码可正常调用全链路；旧编码不可再路由
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/renamed.png\"}]}")));
        String resp = submitAndAwait(sk, "{\"model\":\"new.model\",\"prompt\":\"x\"}");
        assertEquals("SUCCEEDED", field(resp, "status"));
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"old.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @org.junit.jupiter.api.DisplayName("渠道停用联动：其分组成员停用并稳定滑到队尾；恢复渠道不自动恢复成员")
    @Test
    void channelDisableCascadesGroupMembersToTail() throws Exception {
        String admin = adminToken();
        Channel chA = seedChannel("联动渠道A", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-a");
        Channel chB = seedChannel("联动渠道B", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-b");
        ModelGroup g = seedGroup("cascade.model", "0.02", List.of(chA, chB, chA),
                List.of("m-a1", "m-b1", "m-a2"));
        refreshSnapshot();

        // 手动停用渠道 A → 其两个成员停用并滑到队尾（停用块保持原相对顺序），B 成员顶到第一
        mvc.perform(post("/admin/channels/" + chA.getId() + "/disable").header("X-Admin-Token", admin))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/groups/" + g.getId()).header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members[0].upstreamModel").value("m-b1"))
                .andExpect(jsonPath("$.data.members[0].enabled").value(1))
                .andExpect(jsonPath("$.data.members[1].upstreamModel").value("m-a1"))
                .andExpect(jsonPath("$.data.members[1].enabled").value(0))
                .andExpect(jsonPath("$.data.members[1].channelStatus").value(0))
                .andExpect(jsonPath("$.data.members[2].upstreamModel").value("m-a2"))
                .andExpect(jsonPath("$.data.members[2].enabled").value(0));

        // 恢复渠道 A：成员保持停用（是否恢复由管理员在分组里决定），排序不跳动
        mvc.perform(post("/admin/channels/" + chA.getId() + "/enable").header("X-Admin-Token", admin))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/groups/" + g.getId()).header("X-Admin-Token", admin))
                .andExpect(jsonPath("$.data.members[0].enabled").value(1))
                .andExpect(jsonPath("$.data.members[1].enabled").value(0))
                .andExpect(jsonPath("$.data.members[2].enabled").value(0));

        // 自动停用路径（余额/Key 失效）同样联动：B 停用后三成员全停，已停用成员相对顺序不变
        channelOps.autoDisable(chB.getId(), "上游余额耗尽，自动停用");
        mvc.perform(get("/admin/groups/" + g.getId()).header("X-Admin-Token", admin))
                .andExpect(jsonPath("$.data.members[0].upstreamModel").value("m-b1"))
                .andExpect(jsonPath("$.data.members[0].enabled").value(0))
                .andExpect(jsonPath("$.data.members[0].channelStatus").value(4))
                .andExpect(jsonPath("$.data.members[1].upstreamModel").value("m-a1"))
                .andExpect(jsonPath("$.data.members[2].upstreamModel").value("m-a2"));
    }

    @org.junit.jupiter.api.DisplayName("OpenAI 同步族·uuapi 图生图：带参考图自动切 /images/edits（images[].image_url）")
    @Test
    void syncUuapiEditsReferenceImageFlow() throws Exception {
        Channel ch = seedChannel("uuapi同步渠道", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-uu",
                Map.of("refImageStyle", "uuapi"));
        seedGroup("uu-edit.model", "0.02", List.of(ch), List.of("gpt-image-2.5"));
        String sk = seedToken(1L, "sk-uu-edit-aaaaaa", "10.00", null);
        refreshSnapshot();

        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/edits"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}")));
        String resp = submitAndAwait(sk, "{\"model\":\"uu-edit.model\",\"prompt\":\"帮我美化一下\","
                + "\"images\":[\"https://cdn.ref/a.png\"]}");
        assertEquals("SUCCEEDED", field(resp, "status"));
        assertTrue(resp.contains("aGVsbG8="), resp);
        assertEquals(1, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/edits"))).size(),
                "不应重试、不应换渠道，一次命中 edits 端点");
        String upBody = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/edits")))
                .get(0).getBodyAsString();
        assertTrue(upBody.contains("\"image_url\":\"https://cdn.ref/a.png\""), upBody);
        assertTrue(upBody.contains("\"model\":\"gpt-image-2.5\""), upBody);
    }

    @org.junit.jupiter.api.DisplayName("跨协议族故障转移：uuapi(图生图格式)失败 → Crun 重build为 img_urls 原始体，格式不串")
    @Test
    void refImagesRebuiltPerChannelOnCrossFamilyFailover() throws Exception {
        Channel uuapi = seedChannel("uuapi转移源", "OPENAI_ASYNC_TASK", WM.baseUrl() + "/v1", "sk-uu",
                Map.of("flavor", "uu", "refImageStyle", "uuapi", "urlField", "result.data[*].url"));
        Channel crun = seedChannel("crun接盘侠", "CRUN_TASK", WM.baseUrl(), "crun-key");
        seedGroup("cross.model", "0.02", List.of(uuapi, crun), List.of("gpt-image-2.5", "openai/gpt-image-2-5"));
        String sk = seedToken(1L, "sk-cross-aaaaaaa", "10.00", null);
        refreshSnapshot();

        // uuapi 图生图端点 5xx（提交阶段未受理，可转移）→ Crun CreateTask 接手
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/edits/async"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}")));
        WM.stubFor(WireMock.post(urlEqualTo("/client/job/CreateTask"))
                .withHeader("x-api-key", containing("crun-key"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"code\":200,\"message\":\"ok\",\"data\":{\"task_id\":\"ct_cross\"}}")));
        WM.stubFor(WireMock.get(urlEqualTo("/client/job/TaskInfo?task_id=ct_cross"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"code\":200,\"data\":{\"status\":\"success\",\"credits\":3,"
                                + "\"result\":{\"media_urls\":[\"https://files.crun/cross.png\"]}}}")));

        String resp = submitAndAwait(sk, "{\"model\":\"cross.model\",\"prompt\":\"美化一下\","
                + "\"images\":[\"https://cdn.ref/a.png\"]}");

        assertEquals("SUCCEEDED", field(resp, "status"));
        GenerateTask task = taskMapper.selectList(null).getFirst();
        assertEquals(crun.getId(), task.getChannelId(), "应由 Crun 渠道接手");

        // Crun 收到的必须是它自己的契约：input.img_urls 原始 URL，绝无 uuapi 的 images[].image_url 残留
        String crunBody = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/client/job/CreateTask")))
                .get(0).getBodyAsString();
        assertTrue(crunBody.contains("\"img_urls\":[\"https://cdn.ref/a.png\"]"), crunBody);
        assertTrue(crunBody.contains("\"model\":\"openai/gpt-image-2-5\""), crunBody);
        assertFalse(crunBody.contains("image_url"), crunBody);
        assertFalse(crunBody.contains("\"images\""), crunBody);
        // 最终存档的请求体 = 成功候选（Crun）的请求体
        assertTrue(task.getUpstreamRequest().contains("img_urls"), task.getUpstreamRequest());

        // 尝试时间线：uuapi FAILED(5xx) → Crun SUCCESS
        List<TaskAttempt> attempts = attemptMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TaskAttempt>lambdaQuery()
                        .eq(TaskAttempt::getTaskId, task.getId())
                        .orderByAsc(TaskAttempt::getAttemptNo));
        assertEquals(2, attempts.size());
        assertEquals("UPSTREAM_SERVER_ERROR", attempts.get(0).getErrorKind());
        assertEquals(uuapi.getId(), attempts.get(0).getChannelId());
        assertEquals(WM.baseUrl() + "/v1/images/edits/async", attempts.get(0).getUpstreamUrl());
        assertEquals("SUCCESS", attempts.get(1).getOutcome());
        assertEquals(crun.getId(), attempts.get(1).getChannelId());
        assertEquals(WM.baseUrl() + "/client/job/CreateTask", attempts.get(1).getUpstreamUrl());
        assertEquals(WM.baseUrl() + "/client/job/CreateTask", task.getUpstreamUrl());
    }

    @org.junit.jupiter.api.DisplayName("图生图端点由请求形状决定：submitPath 覆盖不短路 edits 切换")
    @Test
    void editsSwitchNotShadowedBySubmitPathOverride() throws Exception {
        Channel ch = seedChannel("覆盖渠道", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up",
                Map.of("refImageStyle", "uuapi"));
        // 模拟向导曾采纳文生图路径的历史配置（旧版会因此把图生图引去文生图端点）
        ch.setPathOverrides("{\"submitPath\":\"/v1/images/generations\"}");
        channelMapper.updateById(ch);
        seedGroup("edit-win.model", "0.02", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-editwin-aaaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/edits"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/edit-win.png\"}]}")));

        String resp = submitAndAwait(sk, "{\"model\":\"edit-win.model\",\"prompt\":\"美化一下\","
                + "\"images\":[\"https://cdn.ref/b.png\"]}");

        assertEquals("SUCCEEDED", field(resp, "status"));
        assertEquals(1, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/edits"))).size(),
                "带参考图必须命中 edits 端点");
        assertEquals(0, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))).size(),
                "submitPath 覆盖不应把图生图请求引去文生图端点");
    }

    @org.junit.jupiter.api.DisplayName("上游 404 没号（容量类）：自动故障转移下一成员，不直接失败")
    @Test
    void modelUnavailable404FailsOverToNextMember() throws Exception {
        Channel dry = seedChannel("没号渠道", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-dry");
        Channel ok = seedChannel("健康渠道", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-ok");
        seedGroup("dry-failover.model", "0.02", List.of(dry, ok), List.of("m-dry", "m-ok"));
        String sk = seedToken(1L, "sk-dry-failo-aaaaaa", "10.00", null);
        refreshSnapshot();

        // 渠道 A：404 + uuapi「账号池没号」文案；渠道 B：正常出图
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withRequestBody(containing("m-dry"))
                .willReturn(aResponse().withStatus(404)
                        .withBody("{\"error\":{\"message\":\"Model \\\"m-dry\\\" is not supported by any "
                                + "configured account in this group\"}}")));
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withRequestBody(containing("m-ok"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/dry-failover.png\"}]}")));

        String resp = submitAndAwait(sk, "{\"model\":\"dry-failover.model\",\"prompt\":\"x\"}");
        assertEquals("SUCCEEDED", field(resp, "status"));

        // 尝试时间线：先 MODEL_UNAVAILABLE（没号渠道），再健康渠道 SUCCESS——而不是任务直接失败
        String taskId = field(resp, "taskId");
        List<TaskAttempt> attempts = attemptMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TaskAttempt>lambdaQuery()
                        .eq(TaskAttempt::getTaskId, taskMapper.selectOne(
                                com.baomidou.mybatisplus.core.toolkit.Wrappers.<GenerateTask>lambdaQuery()
                                        .eq(GenerateTask::getTaskId, taskId)).getId())
                        .orderByAsc(TaskAttempt::getAttemptNo));
        assertEquals(2, attempts.size());
        assertEquals("MODEL_UNAVAILABLE", attempts.get(0).getErrorKind());
        assertEquals("FAILED", attempts.get(0).getOutcome());
        assertEquals("SUCCESS", attempts.get(1).getOutcome());
    }


}
