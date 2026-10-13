package com.drawbridge.integration;

import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.DisableBatch;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @Resource
    com.drawbridge.task.TaskFinisher finisher;
    @Resource
    com.drawbridge.health.ChannelHealthTracker healthTracker;
    @Resource
    com.drawbridge.task.SubmitChain submitChain;
    @Resource
    com.drawbridge.task.SubmitExecutor submitExecutor;
    @Resource
    com.drawbridge.health.AccountRegistry accountRegistry;
    @Resource
    com.drawbridge.task.StuckSweeper stuckSweeper;
    @Resource
    com.drawbridge.domain.mapper.DisableBatchMapper disableBatchMapper;
    @Resource
    com.drawbridge.health.CooldownRegistry cooldownRegistry;

    /**
     * 任务表写库故障注入：包装真实 mapper，测试可按 SQL SET 片段定点让某次 update 抛异常
     * （模拟"上游受理后收尾失败"/"存档时数据库抖动"）。MyBatis mapper 是接口，
     * Mockito spy 的 callRealMethod 无法转发，所以手写动态代理。
     */
    static volatile java.util.function.BiFunction<Object, com.baomidou.mybatisplus.core.conditions.Wrapper<?>,
            RuntimeException> taskUpdateFault;

    @org.springframework.boot.test.context.TestConfiguration
    static class TaskMapperFaultConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        GenerateTaskMapper hookedTaskMapper(GenerateTaskMapper real) {
            return (GenerateTaskMapper) java.lang.reflect.Proxy.newProxyInstance(
                    GenerateTaskMapper.class.getClassLoader(), new Class<?>[]{GenerateTaskMapper.class},
                    (proxy, method, args) -> {
                        if ("update".equals(method.getName()) && taskUpdateFault != null && args.length == 2) {
                            RuntimeException fault = taskUpdateFault.apply(args[0],
                                    (com.baomidou.mybatisplus.core.conditions.Wrapper<?>) args[1]);
                            if (fault != null) {
                                throw fault;
                            }
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    /** 按 update 的 SET 片段定点注入故障，触发一次后自动解除（null entity = 条件更新 update(null, wrapper)）。 */
    private static void faultOnSqlSetOnce(String fragment, RuntimeException error) {
        taskUpdateFault = (entity, wrapper) -> {
            if (entity == null && wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> aw) {
                String sqlSet = aw.getSqlSet();
                if (sqlSet != null && sqlSet.contains(fragment)) {
                    taskUpdateFault = null;
                    return error;
                }
            }
            return null;
        };
    }

    /**
     * 定点故障"领取 UPDATE"（status: SUBMITTED→RUNNING）。领取与回退的 SET/参数高度同形，
     * 单靠 SET 片段会撞车：判别式 = SET 带 status + 绑定参数含 SUBMITTED + WHERE 无 upstream_task_id
     * （回退的 WHERE 带 isNull(upstream_task_id)，清扫④的 SET 不含 status）。
     */
    private static void faultOnClaimOnce(RuntimeException error) {
        taskUpdateFault = (entity, wrapper) -> {
            if (entity == null && wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> aw) {
                String sqlSet = aw.getSqlSet();
                if (sqlSet != null && sqlSet.contains("status")
                        && aw.getParamNameValuePairs().containsValue("SUBMITTED")
                        && !String.valueOf(aw.getSqlSegment()).contains("upstream_task_id")) {
                    taskUpdateFault = null;
                    return error;
                }
            }
            return null;
        };
    }

    /** 同上，但持续生效（压"收尾重试 3 次后仍失败"的兜底路径）。 */
    private static void faultOnSqlSetAlways(String fragment, RuntimeException error) {
        taskUpdateFault = (entity, wrapper) -> {
            if (entity == null && wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> aw) {
                String sqlSet = aw.getSqlSet();
                if (sqlSet != null && sqlSet.contains(fragment)) {
                    return error;
                }
            }
            return null;
        };
    }

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
        taskUpdateFault = null;
        jdbc.update("DELETE FROM task_attempt");
        jdbc.update("DELETE FROM usage_log");
        jdbc.update("DELETE FROM generate_task");
        jdbc.update("DELETE FROM group_member");
        jdbc.update("DELETE FROM model_group");
        jdbc.update("DELETE FROM upstream_model");
        jdbc.update("DELETE FROM api_token");
        jdbc.update("DELETE FROM channel");
        jdbc.update("DELETE FROM upstream_account");
        jdbc.update("DELETE FROM disable_batch");
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
        // v0.4：渠道必须带上游账号键（同 Key 渠道共享并发池），并确保账号行存在
        c.setAccountKey(accountRegistry.computeKey(baseUrl, apiKey));
        accountRegistry.ensureAccount(c.getAccountKey());
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
        assertNotNull(task.getLastPollAt(), "轮询过就应留下最近轮询时间（管理端详情展示轮询活性）");
        // 任务查询接口
        mvc.perform(get("/v1/images/tasks/" + taskId).header("Authorization", "Bearer " + sk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.urls[0]").value("https://cdn.result/2.png"));
    }

    @org.junit.jupiter.api.DisplayName("APIMart 统一任务协议全链：{code,data[0].task_id} 提交壳 + /tasks/{id} 轮询 + url 数组取图")
    @Test
    void apimartFullChain() throws Exception {
        Channel ch = seedChannel("APIMart渠道", "OPENAI_ASYNC_TASK", WM.baseUrl() + "/v1",
                "sk-apa", Map.of(
                        "idField", "data[0].task_id",
                        "queryPath", "/tasks/{id}",
                        "statusField", "data.status",
                        "urlField", "data.result.images[*].url[*]",
                        "refImageStyle", "image_urls",
                        "requestStyle", "ratioTier"));
        seedGroup("nano-banana", "0.02", List.of(ch), List.of("gemini-nano-banana-2.1"));
        String sk = seedToken(1L, "sk-apimart-aaaaa", "10.00", null);
        refreshSnapshot();

        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"code\":200,\"data\":[{\"status\":\"submitted\",\"task_id\":\"task_01KXYZ\"}]}")));
        WM.stubFor(WireMock.get(urlEqualTo("/v1/tasks/task_01KXYZ"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"code\":200,\"data\":{\"id\":\"task_01KXYZ\",\"status\":\"processing\",\"progress\":30}}")));

        String resp = submit(sk, "{\"model\":\"nano-banana\",\"prompt\":\"一只橘猫\","
                + "\"aspectRatio\":\"16:9\",\"resolution\":\"2k\",\"images\":[\"https://ref.example/cat.jpg\"]}");
        String taskId = field(resp, "taskId");
        assertTrue(List.of("SUBMITTED", "RUNNING").contains(field(resp, "status")),
                "提交应立即返回非终态: " + field(resp, "status"));

        // 轮询查到完成：url 是数组，两张全收
        WM.stubFor(WireMock.get(urlEqualTo("/v1/tasks/task_01KXYZ"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"code\":200,\"data\":{\"id\":\"task_01KXYZ\",\"status\":\"completed\",\"progress\":100,"
                                + "\"result\":{\"images\":[{\"url\":[\"https://cdn.apimart/1.png\",\"https://cdn.apimart/2.png\"],"
                                + "\"expires_at\":1791417625}]}}}")));
        GenerateTask task = awaitTerminal(taskId);
        assertEquals("SUCCEEDED", task.getStatus());
        assertEquals("task_01KXYZ", task.getUpstreamTaskId());
        assertEquals(ch.getId(), task.getChannelId());

        // 发上游的请求体（派发线程发出后才有存根记录）：size=比例串直传（ratioTier）、
        // resolution 独立档位字段、参考图 image_urls 数组
        String upstreamBody = WM.findAll(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/images/generations")))
                .get(0).getBodyAsString();
        assertTrue(upstreamBody.contains("\"size\":\"16:9\""), upstreamBody);
        assertTrue(upstreamBody.contains("\"resolution\":\"2k\""), upstreamBody);
        assertTrue(upstreamBody.contains("\"image_urls\":[\"https://ref.example/cat.jpg\"]"), upstreamBody);

        mvc.perform(get("/v1/images/tasks/" + taskId).header("Authorization", "Bearer " + sk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.urls[0]").value("https://cdn.apimart/1.png"))
                .andExpect(jsonPath("$.data.result.urls[1]").value("https://cdn.apimart/2.png"));
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

        // n 已不开放：一个任务固定出一张图
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"image2.5\",\"prompt\":\"x\",\"n\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("一个任务固定出一张图，不支持 n 参数，多张图请提交多个任务"));

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

        // 试跑（WireMock 给 /models 通；模型名须像生图族——同步模型按协议族过滤，chat 类名会被滤掉）
        WM.stubFor(WireMock.get(urlEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"data\":[{\"id\":\"gpt-image-2.5-flare\"},{\"id\":\"gpt-image-2-all\"}]}")));
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
                .andExpect(jsonPath("$.data.descriptions['route.max_channel_attempts']").value("单任务最多尝试的渠道数（跨排队重入累计）"));
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

    @org.junit.jupiter.api.DisplayName("渠道停用联动：其分组成员停用并稳定滑到队尾；恢复渠道联动恢复成员（v0.4）")
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

        // 恢复渠道 A（v0.4）：被联动的成员一并恢复（修"启用不恢复成员"旧缺陷），排序稳定
        mvc.perform(post("/admin/channels/" + chA.getId() + "/enable").header("X-Admin-Token", admin))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/groups/" + g.getId()).header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members[0].upstreamModel").value("m-b1"))
                .andExpect(jsonPath("$.data.members[0].enabled").value(1))
                .andExpect(jsonPath("$.data.members[1].upstreamModel").value("m-a1"))
                .andExpect(jsonPath("$.data.members[1].enabled").value(1))
                .andExpect(jsonPath("$.data.members[2].upstreamModel").value("m-a2"))
                .andExpect(jsonPath("$.data.members[2].enabled").value(1));

        // 自动停用路径（余额/Key 失效）同样联动：m-b1 停用并滑到队尾，其余成员保持启用
        channelOps.autoDisable(chB.getId(), "上游余额耗尽，自动停用");
        mvc.perform(get("/admin/groups/" + g.getId()).header("X-Admin-Token", admin))
                .andExpect(jsonPath("$.data.members[0].upstreamModel").value("m-a1"))
                .andExpect(jsonPath("$.data.members[0].enabled").value(1))
                .andExpect(jsonPath("$.data.members[1].upstreamModel").value("m-a2"))
                .andExpect(jsonPath("$.data.members[1].enabled").value(1))
                .andExpect(jsonPath("$.data.members[2].upstreamModel").value("m-b1"))
                .andExpect(jsonPath("$.data.members[2].enabled").value(0))
                .andExpect(jsonPath("$.data.members[2].channelStatus").value(4));
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

    // ------------------------------------------------------------------ P0 修复回归（2026-10-10）

    private GenerateTask taskByTaskId(String taskId) {
        return taskMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<GenerateTask>lambdaQuery()
                .eq(GenerateTask::getTaskId, taskId));
    }

    @org.junit.jupiter.api.DisplayName("P0-1：上游受理成功后收尾写库失败——绝不换渠道重发（防重复扣费）")
    @Test
    void noRedispatchAfterUpstreamAcceptedWhenFinishWriteFails() throws Exception {
        Channel a = seedChannel("受理渠道", "OPENAI_ASYNC_TASK", WM.baseUrl() + "/v1", "sk-a",
                Map.of("flavor", "nezha"));
        Channel b = seedChannel("接盘渠道", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-b");
        seedGroup("p01.model", "0.01", List.of(a, b), List.of("m", "m"));
        String sk = seedToken(1L, "sk-p01-aaaaaaa", "10.00", null);
        refreshSnapshot();

        // 打桩：写 upstream_task_id 持续失败（比收尾 3 次重试更强，模拟 DB 对该写持续不可用）
        faultOnSqlSetAlways("upstream_task_id",
                new org.springframework.dao.QueryTimeoutException("模拟受理后写库失败"));

        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-a"))
                .willReturn(aResponse().withStatus(202).withBody("{\"id\":\"p01_acc\"}")));
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-b"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/p01-b.png\"}]}")));

        String taskId = field(submit(sk, "{\"model\":\"p01.model\",\"prompt\":\"x\"}"), "taskId");

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertNotNull(taskByTaskId(taskId).getUpstreamRequest()));
        // 给扫描器/轮询器几个 tick，证明不会重发
        Thread.sleep(2500);

        GenerateTask t = taskByTaskId(taskId);
        assertEquals("RUNNING", t.getStatus(), "受理后不换渠道也不伪造终态：保持 RUNNING 等人工/超时兜底");
        assertEquals(1, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-a"))).size(), "渠道 A 只打一次");
        assertEquals(0, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-b"))).size(), "渠道 B 绝不能被打到（防重复出图）");
        assertEquals(0, usageLogMapper.selectCount(null), "无终态则无用量流水");
    }

    @org.junit.jupiter.api.DisplayName("P0-1：发送前数据库异常——不喂熔断不记尝试，任务回退后自动重跑成功")
    @Test
    void dbOutageBeforeSubmitRollsBackAndRetries() throws Exception {
        Channel a = seedChannel("单渠道", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-a");
        seedGroup("p01b.model", "0.01", List.of(a), List.of("m"));
        String sk = seedToken(1L, "sk-p01b-aaaaaaa", "10.00", null);
        refreshSnapshot();

        faultOnSqlSetOnce("upstream_request",
                new org.springframework.dao.QueryTimeoutException("模拟存档时数据库抖动"));

        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/p01b.png\"}]}")));

        String taskId = field(submit(sk, "{\"model\":\"p01b.model\",\"prompt\":\"x\"}"), "taskId");
        GenerateTask t = awaitTerminal(taskId);

        assertEquals("SUCCEEDED", t.getStatus());
        assertEquals(1, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))).size(),
                "回退重跑后上游只应被命中一次");
        assertEquals(1, attemptMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<TaskAttempt>lambdaQuery()
                                .eq(TaskAttempt::getTaskId, t.getId())).size(),
                "DB 异常不算渠道尝试，只留重跑成功那一条");
        assertEquals(1, channelMapper.selectById(a.getId()).getStatus(), "基础设施异常不停用渠道");
        assertEquals(com.drawbridge.health.ChannelHealthTracker.NORMAL,
                healthTracker.healthState(a.getId()), "基础设施异常不喂熔断（熔断器内存状态应保持 NORMAL）");
    }

    @org.junit.jupiter.api.DisplayName("P0-3：幂等键超长拦截；空白键不复用旧任务；跨令牌同键互不影响（按令牌隔离）")
    @Test
    void idempotencyKeyGuards() throws Exception {
        Channel ch = seedChannel("幂等上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("p03.model", "0.01", List.of(ch), List.of("m"));
        String skA = seedToken(1L, "sk-p03a-aaaaaaa", "10.00", null);
        String skB = seedToken(1L, "sk-p03b-aaaaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/p03.png\"}]}")));

        // 65 字符 → 参数错误，不落库不 500
        mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + skA)
                        .header("Content-Type", "application/json")
                        .header("X-Idempotency-Key", "k".repeat(65))
                        .content("{\"model\":\"p03.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        // 空白键 = 无键：连续两次各建各的任务，绝不静默复用
        MvcResult b1 = mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + skA)
                        .header("Content-Type", "application/json")
                        .header("X-Idempotency-Key", "")
                        .content("{\"model\":\"p03.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult b2 = mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + skA)
                        .header("Content-Type", "application/json")
                        .header("X-Idempotency-Key", "   ")
                        .content("{\"model\":\"p03.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(!field(b1.getResponse().getContentAsString(), "taskId")
                .equals(field(b2.getResponse().getContentAsString(), "taskId")), "空白键不得复用任务");

        // 同令牌同键 → 同一任务（幂等）
        MvcResult a1 = mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + skA)
                        .header("Content-Type", "application/json")
                        .header("X-Idempotency-Key", "shared-key")
                        .content("{\"model\":\"p03.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult a2 = mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + skA)
                        .header("Content-Type", "application/json")
                        .header("X-Idempotency-Key", "shared-key")
                        .content("{\"model\":\"p03.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(field(a1.getResponse().getContentAsString(), "taskId"),
                field(a2.getResponse().getContentAsString(), "taskId"));

        // 跨令牌同键：键空间按令牌隔离，各自建任务、互不影响
        mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + skB)
                        .header("Content-Type", "application/json")
                        .header("X-Idempotency-Key", "shared-key")
                        .content("{\"model\":\"p03.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 大小写敏感（utf8mb4_bin）：Order-1 与 order-1 是两个键
        MvcResult c1 = mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + skA)
                        .header("Content-Type", "application/json")
                        .header("X-Idempotency-Key", "Order-1")
                        .content("{\"model\":\"p03.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult c2 = mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + skA)
                        .header("Content-Type", "application/json")
                        .header("X-Idempotency-Key", "order-1")
                        .content("{\"model\":\"p03.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(!field(c1.getResponse().getContentAsString(), "taskId")
                .equals(field(c2.getResponse().getContentAsString(), "taskId")), "键应大小写敏感");
    }

    @org.junit.jupiter.api.DisplayName("契约：n 不开放（一个任务一张图）；单数 image 归一化为参考图首张")
    @Test
    void singularImageMergedAsReferenceAndNRejected() throws Exception {
        Channel ch = seedChannel("单图渠道", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-uu",
                Map.of("refImageStyle", "uuapi"));
        seedGroup("singular.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-singlr-aaaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/edits"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/singular.png\"}]}")));

        // 只传单数 image（OpenAI 习惯）：自动按参考图走 edits 端点
        String resp = submitAndAwait(sk,
                "{\"model\":\"singular.model\",\"prompt\":\"美化\",\"image\":\"https://cdn.ref/only.png\"}");
        assertEquals("SUCCEEDED", field(resp, "status"));
        String upBody = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/edits")))
                .get(0).getBodyAsString();
        assertTrue(upBody.contains("\"image_url\":\"https://cdn.ref/only.png\""), upBody);
        // 归一化后的存档里也是 images 形状（执行链路只认 images）
        GenerateTask t = taskMapper.selectList(null).getFirst();
        assertTrue(t.getBizRequest().contains("\"images\":[\"https://cdn.ref/only.png\"]"),
                t.getBizRequest());

        // image 与 images 同时传：image 排首位
        WM.resetAll();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/edits"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/two.png\"}]}")));
        resp = submitAndAwait(sk, "{\"model\":\"singular.model\",\"prompt\":\"美化\","
                + "\"image\":\"https://cdn.ref/first.png\",\"images\":[\"https://cdn.ref/second.png\"]}");
        assertEquals("SUCCEEDED", field(resp, "status"));
        String body2 = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/edits")))
                .get(0).getBodyAsString();
        assertTrue(body2.contains("\"images\":[{\"image_url\":\"https://cdn.ref/first.png\"},"
                + "{\"image_url\":\"https://cdn.ref/second.png\"}]"), body2);
    }

    @org.junit.jupiter.api.DisplayName("P0-4：分组停用/启用/真删除三分离——停用可恢复，删除有在途拦截且编码可复用")
    @Test
    void groupDisableThenEnableRestoresRouting() throws Exception {
        Channel ch = seedChannel("恢复上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        ModelGroup g = seedGroup("p04.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p04-aaaaaaa", "10.00", null);
        refreshSnapshot();
        String admin = adminToken();

        // 停用（POST /disable）→ 业务方被拒
        mvc.perform(post("/admin/groups/" + g.getId() + "/disable").header("X-Admin-Token", admin))
                .andExpect(status().isOk());
        refreshSnapshot();
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"p04.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("模型不存在或已停用: p04.model"));

        // 启用恢复
        mvc.perform(post("/admin/groups/" + g.getId() + "/enable").header("X-Admin-Token", admin))
                .andExpect(status().isOk());
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/p04.png\"}]}")));
        String resp = submitAndAwait(sk, "{\"model\":\"p04.model\",\"prompt\":\"x\"}");
        assertEquals("SUCCEEDED", field(resp, "status"));

        // 真删除：历史任务已是终态，不拦截；成员条目一并移除；编码即刻可复用
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/admin/groups/" + g.getId()).header("X-Admin-Token", admin))
                .andExpect(status().isOk());
        refreshSnapshot();
        assertEquals(0, memberMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<GroupMember>lambdaQuery()
                        .eq(GroupMember::getGroupId, g.getId())), "成员条目应一并删除");
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"p04.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isBadRequest());
        // 编码可复用（真删除后唯一键已释放）
        mvc.perform(post("/admin/groups").header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"groupCode\":\"p04.model\",\"strategy\":\"FAILOVER\"}"))
                .andExpect(status().isOk());
    }

    @org.junit.jupiter.api.DisplayName("P0-4：删除分组时有在途任务 → 400 拒绝；任务终态后可再删")
    @Test
    void groupDeleteRejectedWhileTasksInflight() throws Exception {
        Channel ch = seedChannel("在途上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        ModelGroup g = seedGroup("p04b.model", "0.01", List.of(ch), List.of("m"));
        String admin = adminToken();

        GenerateTask t = new GenerateTask();
        t.setTaskId("task_p04b_" + System.nanoTime());
        t.setTokenId(1L);
        t.setGroupCode("p04b.model");
        t.setStatus("RUNNING");
        t.setImageCount(1);
        LocalDateTime now = LocalDateTime.now();
        t.setNextPollTime(now);
        t.setExpiresAt(now.plusMinutes(15));
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        taskMapper.insert(t);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/admin/groups/" + g.getId()).header("X-Admin-Token", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("该分组还有 1 个在途任务，请等它们终态后再删除（可先停用分组停止进入）"));
        assertEquals(1, groupMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ModelGroup>lambdaQuery()
                        .eq(ModelGroup::getId, g.getId())), "分组应仍在");

        jdbc.update("UPDATE generate_task SET status='FAILED' WHERE id=" + t.getId());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/admin/groups/" + g.getId()).header("X-Admin-Token", admin))
                .andExpect(status().isOk());
        assertEquals(0, groupMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ModelGroup>lambdaQuery()
                        .eq(ModelGroup::getId, g.getId())), "终态后删除应成功");
    }

    // ------------------------------------------------------------------ P1 批次（2026-10-10 晚）

    @org.junit.jupiter.api.DisplayName("修复：轮询 404 宽限——连续 4 次都 404 才判 UPSTREAM_TASK_LOST（防最终一致性抖动误杀已付费任务）")
    @Test
    void pollTaskLostFailsFast() throws Exception {
        Channel ch = seedChannel("丢任务上游", "OPENAI_ASYNC_TASK", WM.baseUrl() + "/v1", "sk-up",
                Map.of("flavor", "nezha"));
        seedGroup("p1lost.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p1lost-aaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(202).withBody("{\"id\":\"j_lost\"}")));
        WM.stubFor(WireMock.get(urlEqualTo("/v1/images/jobs/j_lost"))
                .willReturn(aResponse().withStatus(404).withBody("{\"error\":{\"message\":\"任务不存在\"}}")));

        String taskId = field(submit(sk, "{\"model\":\"p1lost.model\",\"prompt\":\"x\"}"), "taskId");
        GenerateTask t = awaitTerminal(taskId);
        assertEquals("FAILED", t.getStatus());
        assertEquals("UPSTREAM_TASK_LOST", t.getErrorCode());
        assertTrue(t.getErrorMsg().contains("连续 4 次"), t.getErrorMsg());
        // 恰好宽限 TASK_NOT_FOUND_GRACE=4 次后终态：既不一次误杀，也不空转到生命周期超时
        WM.verify(4, WireMock.getRequestedFor(urlEqualTo("/v1/images/jobs/j_lost")));
    }

    @org.junit.jupiter.api.DisplayName("P1-4：Crun 任务被取消（cancelled）——识别为失败终态，不空转")
    @Test
    void crunCancelledEndsTerminal() throws Exception {
        Channel ch = seedChannel("取消上游", "CRUN_TASK", WM.baseUrl(), "crun-key");
        seedGroup("p1cancel.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p1cancel-aaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/client/job/CreateTask"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"code\":200,\"data\":{\"task_id\":\"ct_cancel\"}}")));
        WM.stubFor(WireMock.get(urlEqualTo("/client/job/TaskInfo?task_id=ct_cancel"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"code\":200,\"data\":{\"status\":\"cancelled\"}}")));

        String taskId = field(submit(sk, "{\"model\":\"p1cancel.model\",\"prompt\":\"x\"}"), "taskId");
        GenerateTask t = awaitTerminal(taskId);
        assertEquals("FAILED", t.getStatus());
        assertEquals("UPSTREAM_TASK_FAILED", t.getErrorCode());
        assertTrue(t.getErrorMsg().contains("取消"), t.getErrorMsg());
    }

    @org.junit.jupiter.api.DisplayName("P1-5：在途任务的渠道被改协议——快速失败 CHANNEL_MISCONFIGURED，不空转")
    @Test
    void protocolChangedFailsInflightFast() throws Exception {
        Channel ch = seedChannel("改协议渠道", "OPENAI_ASYNC_TASK", WM.baseUrl() + "/v1", "sk-up",
                Map.of("flavor", "nezha"));
        seedGroup("p1proto.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p1proto-aaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(202).withBody("{\"id\":\"j_proto\"}")));

        String taskId = field(submit(sk, "{\"model\":\"p1proto.model\",\"prompt\":\"x\"}"), "taskId");
        // 等任务真正受理在途（防派发与协议变更赛跑：受理前改协议=另一条链路的用例）
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertNotNull(taskByTaskId(taskId).getUpstreamTaskId()));
        // 任务已受理在途：把渠道协议改成同步族（轮询不再可用）
        Channel c = channelMapper.selectById(ch.getId());
        c.setProtocol("OPENAI_IMAGES_SYNC");
        channelMapper.updateById(c);
        configService.refresh();

        GenerateTask t = awaitTerminal(taskId);
        assertEquals("FAILED", t.getStatus());
        assertEquals("CHANNEL_MISCONFIGURED", t.getErrorCode());
    }

    @org.junit.jupiter.api.DisplayName("P1-9：分辨率契约端到端——方向归档/档位降级/Crun 直译/非法入参拦截")
    @Test
    void resolutionContractEndToEnd() throws Exception {
        Channel syncCh = seedChannel("比例同步", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-a");
        Channel crunCh = seedChannel("比例Crun", "CRUN_TASK", WM.baseUrl(), "crun-key");
        seedGroup("p1rs.model", "0.01", List.of(syncCh), List.of("m-sync"));
        seedGroup("p1rc.model", "0.01", List.of(crunCh), List.of("m-crun"));
        String sk = seedToken(1L, "sk-p1r-aaaaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/r.png\"}]}")));
        WM.stubFor(WireMock.post(urlEqualTo("/client/job/CreateTask"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"code\":200,\"data\":{\"task_id\":\"ct_r\"}}")));
        WM.stubFor(WireMock.get(urlEqualTo("/client/job/TaskInfo?task_id=ct_r"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"code\":200,\"data\":{\"status\":\"success\","
                                + "\"result\":{\"media_urls\":[\"https://files.crun/r.png\"]}}}")));

        // 竖版 9:16 → 30 档对照表 1k 档（档位缺省按 1k）
        submitAndAwait(sk, "{\"model\":\"p1rs.model\",\"prompt\":\"PORTRAIT-MARK\",\"aspectRatio\":\"9:16\"}");
        String portrait = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations")))
                .stream().filter(r -> r.getBodyAsString().contains("PORTRAIT-MARK")).findFirst().orElseThrow()
                .getBodyAsString();
        assertTrue(portrait.contains("\"size\":\"720x1280\""), portrait);

        // 4k + 16:9 → 3840x2160（不再降级，30 档对照表精确定档）
        submitAndAwait(sk, "{\"model\":\"p1rs.model\",\"prompt\":\"DEGRADE-MARK\",\"aspectRatio\":\"16:9\",\"resolution\":\"4k\"}");
        String degraded = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations")))
                .stream().filter(r -> r.getBodyAsString().contains("DEGRADE-MARK")).findFirst().orElseThrow()
                .getBodyAsString();
        assertTrue(degraded.contains("\"size\":\"3840x2160\""), degraded);

        // 缺省（不传比例/档位）→ 不带 size：模型用自己的默认，修图不裁方图
        submitAndAwait(sk, "{\"model\":\"p1rs.model\",\"prompt\":\"DEFAULT-MARK\"}");
        String def = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations")))
                .stream().filter(r -> r.getBodyAsString().contains("DEFAULT-MARK")).findFirst().orElseThrow()
                .getBodyAsString();
        assertFalse(def.contains("\"size\""), def);

        // 旧版 size（deprecated）：原样透传不做有损换算（1792x1024 是 dall-e-3 的合法横版尺寸）
        submitAndAwait(sk, "{\"model\":\"p1rs.model\",\"prompt\":\"LEGACY-SIZE\",\"size\":\"1792x1024\"}");
        String legacy = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations")))
                .stream().filter(r -> r.getBodyAsString().contains("LEGACY-SIZE")).findFirst().orElseThrow()
                .getBodyAsString();
        assertTrue(legacy.contains("\"size\":\"1792x1024\""), legacy);
        assertFalse(legacy.contains("1536x1024"), legacy);

        // Crun：没传比例/档位 → 不带 aspect_ratio/resolution（不逼不支持这俩参数的模型吃 422）
        submitAndAwait(sk, "{\"model\":\"p1rc.model\",\"prompt\":\"CRUN-BARE\"}");
        String crunBare = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/client/job/CreateTask")))
                .stream().filter(r -> r.getBodyAsString().contains("CRUN-BARE")).findFirst().orElseThrow()
                .getBodyAsString();
        assertFalse(crunBare.contains("aspect_ratio"), crunBare);
        assertFalse(crunBare.contains("resolution"), crunBare);

        // Crun 直译：21:9 + 2k 原样透传
        submitAndAwait(sk, "{\"model\":\"p1rc.model\",\"prompt\":\"CRUN-MARK\",\"aspectRatio\":\"21:9\",\"resolution\":\"2k\"}");
        String crunBody = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/client/job/CreateTask")))
                .stream().filter(r -> r.getBodyAsString().contains("CRUN-MARK")).findFirst().orElseThrow()
                .getBodyAsString();
        assertTrue(crunBody.contains("\"aspect_ratio\":\"21:9\""), crunBody);
        assertTrue(crunBody.contains("\"resolution\":\"2k\""), crunBody);

        // 非法入参：比例格式/未知档位/超长 URL → 400
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"p1rs.model\",\"prompt\":\"x\",\"aspectRatio\":\"six-nine\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"p1rs.model\",\"prompt\":\"x\",\"resolution\":\"8k\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"p1rs.model\",\"prompt\":\"x\",\"images\":[\"https://cdn.ref/" + "a".repeat(2100) + ".png\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("参考图 URL 过长（>2048 字符）"));
    }

    @org.junit.jupiter.api.DisplayName("P1-3：uu 异步任务成功但只回 b64——转 data URI 收下，不再判失败丢图")
    @Test
    void uuAsyncB64SuccessCollected() throws Exception {
        Channel ch = seedChannel("b64异步", "OPENAI_ASYNC_TASK", WM.baseUrl() + "/v1", "sk-up",
                Map.of("flavor", "uu"));
        seedGroup("p1b64.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p1b64-aaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations/async"))
                .willReturn(aResponse().withStatus(200).withBody("{\"task_id\":\"uu_b64\"}")));
        WM.stubFor(WireMock.get(urlEqualTo("/v1/images/tasks/uu_b64"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"status\":\"succeeded\",\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}")));

        String resp = submitAndAwait(sk, "{\"model\":\"p1b64.model\",\"prompt\":\"x\"}");
        assertEquals("SUCCEEDED", field(resp, "status"));
        assertTrue(resp.contains("data:image/png;base64,aGVsbG8="), resp);
    }

    @org.junit.jupiter.api.DisplayName("P0-5：管理端任务详情时间线暴露每次尝试命中的上游 URL")
    @Test
    void taskDetailExposesAttemptUpstreamUrl() throws Exception {
        Channel ch = seedChannel("详情上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("p05.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p05-aaaaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/p05.png\"}]}")));

        String taskId = field(submitAndAwait(sk, "{\"model\":\"p05.model\",\"prompt\":\"x\"}"), "taskId");
        GenerateTask t = taskByTaskId(taskId);

        mvc.perform(get("/admin/tasks/detail/" + t.getId()).header("X-Admin-Token", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attempts[0].upstreamUrl")
                        .value(WM.baseUrl() + "/v1/images/generations"))
                .andExpect(jsonPath("$.data.task.upstreamUrl")
                        .value(WM.baseUrl() + "/v1/images/generations"));
    }

    @org.junit.jupiter.api.DisplayName("P0-8：options 超 64KB 提交时拦截")
    @Test
    void oversizeOptionsRejected() throws Exception {
        Channel ch = seedChannel("大包上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("p08a.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p08a-aaaaaaa", "10.00", null);
        refreshSnapshot();

        mvc.perform(post("/v1/images/tasks")
                        .header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"p08a.model\",\"prompt\":\"x\","
                                + "\"options\":{\"blob\":\"" + "x".repeat(70 * 1024) + "\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @org.junit.jupiter.api.DisplayName("P0-8：大 options 完整执行且完整存档，不再被截断丢参数")
    @Test
    void largeOptionsStoredFullyAndExecuted() throws Exception {
        Channel ch = seedChannel("大包上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("p08b.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p08b-aaaaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/p08b.png\"}]}")));

        String body = "{\"model\":\"p08b.model\",\"prompt\":\"x\","
                + "\"options\":{\"banner\":\"" + "B".repeat(30 * 1024) + "TAIL\"}}";
        String resp = submitAndAwait(sk, body);

        assertEquals("SUCCEEDED", field(resp, "status"));
        String upBody = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations")))
                .get(0).getBodyAsString();
        assertTrue(upBody.contains("TAIL"), "30KB options 必须完整发到上游");
        GenerateTask t = taskMapper.selectList(null).getFirst();
        assertTrue(t.getBizRequest().contains("TAIL"), "biz_request 必须完整存档（执行入参来源）");
    }

    @org.junit.jupiter.api.DisplayName("P0-8：body_max_bytes 调小只截断 upstream_request 存档，执行入参完整；=0 也不再 500")
    @Test
    void bodyArchiveTruncatedButExecutionFull() throws Exception {
        Channel ch = seedChannel("截档上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("p08c.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p08c-aaaaaaa", "10.00", null);
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/p08c.png\"}]}")));
        try {
            config.updateAll(Map.of(ConfigKeys.RETENTION_BODY_MAX_BYTES.getKey(), "1024"));
            refreshSnapshot();
            String prompt = "P".repeat(3_000) + "END-MARKER";
            String resp = submitAndAwait(sk,
                    "{\"model\":\"p08c.model\",\"prompt\":\"" + prompt + "\"}");
            assertEquals("SUCCEEDED", field(resp, "status"));

            GenerateTask t = taskMapper.selectList(null).getFirst();
            assertTrue(t.getBizRequest().contains("END-MARKER")
                    && !t.getBizRequest().contains("[truncated]"), "biz_request 完整不截断");
            assertTrue(t.getUpstreamRequest().contains("[truncated]")
                    && t.getUpstreamRequest().length() < 1_100, "upstream_request 存档按 1024 截断");
            String upBody = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations")))
                    .get(0).getBodyAsString();
            assertTrue(upBody.contains("END-MARKER"), "截断只影响存档，不影响执行");

            // =0：防御性回退默认，不再 500
            config.updateAll(Map.of(ConfigKeys.RETENTION_BODY_MAX_BYTES.getKey(), "0"));
            refreshSnapshot();
            String resp2 = submitAndAwait(sk, "{\"model\":\"p08c.model\",\"prompt\":\"again\"}");
            assertEquals("SUCCEEDED", field(resp2, "status"));
        } finally {
            config.updateAll(Map.of(ConfigKeys.RETENTION_BODY_MAX_BYTES.getKey(), "32768"));
            refreshSnapshot();
        }
    }

    @org.junit.jupiter.api.DisplayName("P0-7：超大结果如实判失败 RESULT_TOO_LARGE（不再'成功但没图'）")
    @Test
    void oversizeResultFailsWithResultTooLarge() {
        GenerateTask t = new GenerateTask();
        t.setTaskId("task_p07_" + System.nanoTime());
        t.setTokenId(1L);
        t.setGroupCode("p07.model");
        t.setStatus("RUNNING");
        t.setImageCount(1);
        LocalDateTime now = LocalDateTime.now();
        t.setNextPollTime(now);
        t.setExpiresAt(now.plusMinutes(15));
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        taskMapper.insert(t);

        finisher.complete(t.getId(),
                List.of("data:image/png;base64," + "A".repeat(15_100_000)), true);

        GenerateTask after = taskMapper.selectById(t.getId());
        assertEquals("FAILED", after.getStatus(), "业务方拿不到图就该是失败，不能'成功但没图'");
        assertEquals("RESULT_TOO_LARGE", after.getErrorCode());
        assertTrue(after.getErrorMsg().contains("体积"), after.getErrorMsg());
    }

    // ------------------------------------------------------------------ 第三轮交叉测试修复（2026-10-11）

    /** 直插一条 SUBMITTED 任务（绕过控制器；rerunCount/upstreamTaskId 可预置）。 */
    private GenerateTask insertSubmittedTask(String groupCode, int rerunCount, String upstreamTaskId) {
        GenerateTask t = new GenerateTask();
        t.setTaskId("task_p3_" + System.nanoTime());
        t.setTokenId(1L);
        t.setGroupCode(groupCode);
        t.setStatus("SUBMITTED");
        t.setImageCount(1);
        t.setRerunCount(rerunCount);
        t.setUpstreamTaskId(upstreamTaskId);
        LocalDateTime now = LocalDateTime.now();
        t.setNextPollTime(now);
        t.setExpiresAt(now.plusMinutes(15));
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        taskMapper.insert(t);
        return t;
    }

    @org.junit.jupiter.api.DisplayName("修复：参考图数量网关不设上限——各模型容量不同（gpt-image 系 14 张），超出由上游精确报错")
    @Test
    void multiReferenceImagesNoGatewayCap() throws Exception {
        Channel ch = seedChannel("多图上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("p3imgs.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p3imgs-aaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/imgs.png\"}]}")));

        // 5 张参考图（超过旧网关上限 4）：网关必须放行，上游自己判断模型容量
        StringBuilder imgs = new StringBuilder("[");
        for (int i = 1; i <= 5; i++) {
            if (i > 1) imgs.append(',');
            imgs.append("\"https://cdn.ref/pic").append(i).append(".png\"");
        }
        imgs.append(']');
        String resp = submitAndAwait(sk,
                "{\"model\":\"p3imgs.model\",\"prompt\":\"x\",\"images\":" + imgs + "}");
        assertEquals("SUCCEEDED", field(resp, "status"), "网关不得代上游限制参考图数量");
        String body = WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations")))
                .get(0).getBodyAsString();
        for (int i = 1; i <= 5; i++) {
            assertTrue(body.contains("https://cdn.ref/pic" + i + ".png"), "第 " + i + " 张参考图应原样透传");
        }
    }

    @org.junit.jupiter.api.DisplayName("修复：删除分组时唯一授权它的令牌变 []（零权限），重建同名分组也拿不回权限")
    @Test
    void groupDeleteCascadesExclusiveTokenToEmptyList() throws Exception {
        Channel ch = seedChannel("级联上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        ModelGroup g = seedGroup("p3casc.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p3casc-aaaaa", "10.00", List.of("p3casc.model"));
        refreshSnapshot();
        String admin = adminToken();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/admin/groups/" + g.getId()).header("X-Admin-Token", admin))
                .andExpect(status().isOk());

        ApiToken token = tokenMapper.selectList(null).getFirst();
        assertEquals("[]", token.getAllowedGroups(),
                "唯一授权被删分组的令牌必须写成 []（零权限）：null=全部分组是权限放大，保留旧值=权限漂移");
        // 管理端列表：[] 必须能和 null（全部）区分开，否则零权限被显示成"全部"
        mvc.perform(get("/admin/tokens").header("X-Admin-Token", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].allowedGroups").isArray())
                .andExpect(jsonPath("$.data[0].allowedGroups.length()").value(0));

        // 重建同名分组 → 该令牌依然 403（权限不漂移）
        mvc.perform(post("/admin/groups").header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"groupCode\":\"p3casc.model\",\"strategy\":\"FAILOVER\"}"))
                .andExpect(status().isOk());
        refreshSnapshot();
        mvc.perform(post("/v1/images/tasks").header("Authorization", "Bearer " + sk)
                        .header("Content-Type", "application/json")
                        .content("{\"model\":\"p3casc.model\",\"prompt\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @org.junit.jupiter.api.DisplayName("修复：令牌编辑授权分组——清空选择恢复「全部」（null 显式落库），扩宽缩减即时生效")
    @Test
    void tokenGroupEditClearRestoresAll() throws Exception {
        Channel ch = seedChannel("令牌上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        ModelGroup g = seedGroup("p3tok.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p3tok-aaaaaa", "10.00", List.of("p3tok.model"));
        refreshSnapshot();
        String admin = adminToken();
        ApiToken token = tokenMapper.selectList(null).getFirst();

        // 缩减为空 = 恢复「全部」：库里必须真的是 null（updateById 跳 null 字段的存量 bug 已修）
        mvc.perform(put("/admin/tokens/" + token.getId()).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"allowedGroups\":[]}"))
                .andExpect(status().isOk());
        assertEquals(null, tokenMapper.selectById(token.getId()).getAllowedGroups(),
                "清空授权必须显式写 null（=全部），不能因跳过 null 字段而保持旧值");

        // 扩宽到另一个分组：正常写入
        mvc.perform(put("/admin/tokens/" + token.getId()).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"allowedGroups\":[\"p3tok.model\",\"other.model\"]}"))
                .andExpect(status().isOk());
        ApiToken after = tokenMapper.selectById(token.getId());
        assertEquals("[\"p3tok.model\",\"other.model\"]", after.getAllowedGroups());

        // 管理端列表三态可区分：限定列表 → 数组；再清空 → null（前端据此显示「全部」）
        mvc.perform(get("/admin/tokens").header("X-Admin-Token", admin))
                .andExpect(jsonPath("$.data[0].allowedGroups.length()").value(2));
        mvc.perform(put("/admin/tokens/" + token.getId()).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"allowedGroups\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/tokens").header("X-Admin-Token", admin))
                .andExpect(jsonPath("$.data[0].allowedGroups").value(org.hamcrest.Matchers.nullValue()));

        // 恢复「全部」后确实能调用（鉴权 canUse(null)=true 全链路）
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/tok.png\"}]}")));
        String resp = submitAndAwait(sk, "{\"model\":\"p3tok.model\",\"prompt\":\"x\"}");
        assertEquals("SUCCEEDED", field(resp, "status"));
    }

    @org.junit.jupiter.api.DisplayName("修复：领取 UPDATE 自身失败（连接池打满）不回退——等下轮扫描恢复，恰好提交一次")
    @Test
    void claimFaultDoesNotRollbackOthersTask() throws Exception {
        Channel ch = seedChannel("领取上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("p3claim.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-p3claim-aaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/claim.png\"}]}")));

        faultOnClaimOnce(new org.springframework.dao.DataAccessResourceFailureException("连接池打满"));
        String taskId = field(submit(sk, "{\"model\":\"p3claim.model\",\"prompt\":\"x\"}"), "taskId");
        // 故障（Once）已解除，等扫描器把它正常跑完
        GenerateTask t = awaitTerminal(taskId);
        assertEquals("SUCCEEDED", t.getStatus());
        assertEquals(1, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))).size(),
                "领取失败不回退不重跑：上游恰好被调用一次，无重复扣费");
        assertEquals(1, t.getAttemptCount(), "只有恢复后的那一次链路记了尝试");
        assertEquals(0, t.getRerunCount(), "领取失败路径绝不产生回退计数");
    }

    @org.junit.jupiter.api.DisplayName("修复：回退重跑超 5 次（独立列 rerun_count）判 INTERNAL_ERROR，不再热循环")
    @Test
    void rerunCapFailsInternalError() throws Exception {
        Channel ch = seedChannel("重跑上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("p3cap.model", "0.01", List.of(ch), List.of("m"));
        refreshSnapshot();
        GenerateTask t = insertSubmittedTask("p3cap.model", 5, null);
        faultOnSqlSetAlways("upstream_request",
                new org.springframework.dao.DataAccessResourceFailureException("db down"));
        submitExecutor.kick(t.getId());
        GenerateTask after = awaitTerminal(t.getTaskId());
        assertEquals("FAILED", after.getStatus());
        assertEquals("INTERNAL_ERROR", after.getErrorCode());
    }

    @org.junit.jupiter.api.DisplayName("修复：回退超限但上游已受理（有任务号）的任务不判失败——保持 RUNNING 交轮询/清扫")
    @Test
    void rerunCapSparesAcceptedTask() throws Exception {
        Channel ch = seedChannel("重跑上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("p3cap2.model", "0.01", List.of(ch), List.of("m"));
        refreshSnapshot();
        GenerateTask t = insertSubmittedTask("p3cap2.model", 5, "ct_keep_alive");
        faultOnSqlSetAlways("upstream_request",
                new org.springframework.dao.DataAccessResourceFailureException("db down"));
        submitExecutor.kick(t.getId());
        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            GenerateTask after = taskMapper.selectById(t.getId());
            assertEquals("RUNNING", after.getStatus(), "上游已受理的任务超限也不能判失败");
            assertNull(after.getErrorCode());
        });
    }

    @org.junit.jupiter.api.DisplayName("修复：停机中断落在发请求前 → 链路中止，绝不碰上游（执行器随后安全回退）")
    @Test
    void shutdownInterruptBeforeRequestAbortsCleanly() throws Exception {
        Channel ch = seedChannel("中断上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("p3int.model", "0.01", List.of(ch), List.of("m"));
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/int.png\"}]}")));
        GenerateTask t = insertSubmittedTask("p3int.model", 0, null);
        jdbc.update("UPDATE generate_task SET status='RUNNING', channel_id=" + ch.getId() + " WHERE id=" + t.getId());

        Thread.currentThread().interrupt();
        try {
            assertThrows(com.drawbridge.task.SubmissionAbortedException.class,
                    () -> submitChain.run(t.getId()));
        } finally {
            Thread.interrupted(); // 清掉测试线程的中断标志，不污染后续用例
        }
        GenerateTask after = taskMapper.selectById(t.getId());
        assertEquals(0, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))).size(),
                "中断后绝不向上游发请求");
        assertNull(after.getUpstreamRequest(), "没到发送点，不应留下请求存档（重跑安全）");
    }

    @org.junit.jupiter.api.DisplayName("修复：停机中断落在已发请求之后 → SYSTEM_SHUTDOWN 诚实终态，不再打满剩余渠道")
    @Test
    void shutdownInterruptAfterRequestSentFailsHonestly() throws Exception {
        Channel ch = seedChannel("中断上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-up");
        seedGroup("p3int2.model", "0.01", List.of(ch), List.of("m"));
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(8000)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/slow.png\"}]}")));
        GenerateTask t = insertSubmittedTask("p3int2.model", 0, null);
        jdbc.update("UPDATE generate_task SET status='RUNNING', channel_id=" + ch.getId() + " WHERE id=" + t.getId());

        Thread runner = new Thread(() -> submitChain.run(t.getId()));
        runner.start();
        Thread.sleep(600);   // 已进入 HTTP 等待（requestSent=true），上游可能已收到请求
        runner.interrupt();
        runner.join(10_000);
        GenerateTask after = taskMapper.selectById(t.getId());
        assertEquals("FAILED", after.getStatus());
        assertEquals("SYSTEM_SHUTDOWN", after.getErrorCode());
        assertTrue(after.getErrorMsg().contains("停机"), after.getErrorMsg());
        assertEquals(1, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))).size(),
                "中断后不向后续渠道重发（上游可能已受理，重发=重复扣费）");
    }

    @org.junit.jupiter.api.DisplayName("修复：上游 2xx 但响应非 JSON → UPSTREAM_BAD_RESPONSE 终态，绝不换渠道重发（防重复扣费）")
    @Test
    void badResponse2xxFailsWithoutRedirect() throws Exception {
        Channel ch1 = seedChannel("垃圾响应A", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-a");
        Channel ch2 = seedChannel("垃圾响应B", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-b");
        seedGroup("p3bad.model", "0.01", List.of(ch1, ch2), List.of("m1", "m2"));
        String sk = seedToken(1L, "sk-p3bad-aaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200).withBody("<html>gateway garbage</html>")));

        String taskId = field(submit(sk, "{\"model\":\"p3bad.model\",\"prompt\":\"x\"}"), "taskId");
        GenerateTask t = awaitTerminal(taskId);
        assertEquals("FAILED", t.getStatus());
        assertEquals("UPSTREAM_BAD_RESPONSE", t.getErrorCode());
        assertEquals(1, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))).size(),
                "2xx 垃圾响应绝不换渠道重发（上游可能已出图）");
    }

    // ------------------------------------------------------------------ v0.4 排队与并发语义

    private void setCapacityOf(Channel ch, String apiKey, int capacity) {
        accountRegistry.setCapacity(accountRegistry.computeKey(ch.getBaseUrl(), apiKey), capacity);
    }

    @org.junit.jupiter.api.DisplayName("v0.4：账号容量=1 时三连发——1 个在跑 2 个排队，终态后按提交顺序依次补位")
    @Test
    void queueFillsThenDrainsInFairOrder() throws Exception {
        Channel ch = seedChannel("容量1上游", "OPENAI_ASYNC_TASK", WM.baseUrl() + "/v1",
                "sk-cap1", Map.of("flavor", "nezha"));
        seedGroup("q1.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-q1-aaaaaaaa", "10.00", null);
        setCapacityOf(ch, "sk-cap1", 1);
        refreshSnapshot();

        // 异步受理返回随机任务号；轮询先挂起
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(202).withBody("{\"id\":\"job_{{randomInt lower=1 upper=999999}}\"}")
                        .withTransformers("response-template")));
        WM.stubFor(WireMock.get(WireMock.urlMatching("/v1/images/jobs/job_.*"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"processing\"}")));

        String id1 = field(submit(sk, "{\"model\":\"q1.model\",\"prompt\":\"一\"}"), "taskId");
        String id2 = field(submit(sk, "{\"model\":\"q1.model\",\"prompt\":\"二\"}"), "taskId");
        String id3 = field(submit(sk, "{\"model\":\"q1.model\",\"prompt\":\"三\"}"), "taskId");

        // 容量 1：只有队头拿到槽位，其余排队；上游只被打 1 次
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(1, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))).size()));
        assertEquals("RUNNING", taskByTaskId(id1).getStatus(), "队头在跑");
        assertEquals("SUBMITTED", taskByTaskId(id2).getStatus(), "第 2 单排队");
        assertEquals("SUBMITTED", taskByTaskId(id3).getStatus(), "第 3 单排队");
        assertNotNull(taskByTaskId(id1).getChannelId(), "在跑任务已绑定渠道");

        // 放行轮询成功：task1 终态 → 唤醒派发 → task2 补位 → 又终态 → task3 补位
        WM.stubFor(WireMock.get(WireMock.urlMatching("/v1/images/jobs/job_.*"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"status\":\"succeeded\",\"data\":[{\"url\":\"https://cdn.result/q1.png\"}]}")));
        awaitTerminal(id3);
        assertEquals("SUCCEEDED", taskByTaskId(id1).getStatus());
        assertEquals("SUCCEEDED", taskByTaskId(id2).getStatus());
        assertEquals("SUCCEEDED", taskByTaskId(id3).getStatus());
        assertEquals(3, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))).size(),
                "容量 1 时上游总共只应被受理 3 次（串行补位）");

        // 公平序：task2 的成功尝试必须早于 task3（按提交时间出队，不插队）
        List<TaskAttempt> successes = attemptMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TaskAttempt>lambdaQuery()
                        .eq(TaskAttempt::getOutcome, "SUCCESS")
                        .orderByAsc(TaskAttempt::getCreatedAt));
        assertEquals(3, successes.size());
        Long t2Id = taskByTaskId(id2).getId();
        Long t3Id = taskByTaskId(id3).getId();
        Map<Long, TaskAttempt> attemptByTask = successes.stream().collect(java.util.stream.Collectors
                .toMap(TaskAttempt::getTaskId, a -> a));
        assertTrue(attemptByTask.get(t2Id).getCreatedAt()
                        .isBefore(attemptByTask.get(t3Id).getCreatedAt()),
                "第 2 单必须先于第 3 单获得槽位（先来先服务）");
    }

    @org.junit.jupiter.api.DisplayName("v0.4：单令牌排队上限——超过即拒 TOO_MANY_QUEUED，不占队列")
    @Test
    void tokenQueueCapRejectsOverflow() throws Exception {
        Channel ch = seedChannel("容量1令牌", "OPENAI_ASYNC_TASK", WM.baseUrl() + "/v1",
                "sk-cap2", Map.of("flavor", "nezha"));
        seedGroup("q2.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-q2-aaaaaaaa", "10.00", null);
        setCapacityOf(ch, "sk-cap2", 1);
        config.updateAll(Map.of(ConfigKeys.TOKEN_MAX_QUEUED.getKey(), "2"));
        try {
            refreshSnapshot();
            WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                    .willReturn(aResponse().withStatus(202).withBody("{\"id\":\"job_q2\"}")));
            WM.stubFor(WireMock.get(urlEqualTo("/v1/images/jobs/job_q2"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"processing\"}")));

            submit(sk, "{\"model\":\"q2.model\",\"prompt\":\"1\"}");   // 在跑
            // 等第 1 单真正被领走在跑，排队计数才纯净（否则领走前的瞬时会把它也算进排队）
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals(1, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))).size()));
            submit(sk, "{\"model\":\"q2.model\",\"prompt\":\"2\"}");   // 排队 1
            submit(sk, "{\"model\":\"q2.model\",\"prompt\":\"3\"}");   // 排队 2（达上限）

            // 第 4 单：排队已满 → 429 + TOO_MANY_QUEUED
            mvc.perform(post("/v1/images/tasks")
                            .header("Authorization", "Bearer " + sk)
                            .header("Content-Type", "application/json")
                            .content("{\"model\":\"q2.model\",\"prompt\":\"4\"}"))
                    .andExpect(status().is(429))
                    .andExpect(jsonPath("$.code").value(42902));
            Long queued = taskMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<GenerateTask>lambdaQuery().eq(GenerateTask::getStatus, "SUBMITTED"));
            assertEquals(2, queued, "被拒的任务不应入队");
        } finally {
            config.updateAll(Map.of(ConfigKeys.TOKEN_MAX_QUEUED.getKey(), "20"));
        }
    }

    @org.junit.jupiter.api.DisplayName("v0.4：排队死线——容量 0 暂停派发，超时判 QUEUE_WAIT_TIMEOUT 而不是无限等")
    @Test
    void queueDeadlineTimesOutWithExplicitCode() throws Exception {
        Channel ch = seedChannel("暂停上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-q3");
        seedGroup("q3.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-q3-aaaaaaaa", "10.00", null);
        setCapacityOf(ch, "sk-q3", 0); // 容量 0 = 暂停派发
        config.updateAll(Map.of(ConfigKeys.TASK_QUEUE_MAX_WAIT_MS.getKey(), "500"));
        try {
            refreshSnapshot();
            String taskId = field(submit(sk, "{\"model\":\"q3.model\",\"prompt\":\"x\"}"), "taskId");
            assertEquals("SUBMITTED", taskByTaskId(taskId).getStatus());
            // DATETIME 列秒级精度：跨过 1 秒边界再清扫，避免秒内取整导致 expires 比较失真
            Thread.sleep(1500);
            stuckSweeper.sweep(); // 兜底清扫的 expires 巡检立即执行（不等 60s 节拍）
            GenerateTask t = taskByTaskId(taskId);
            assertEquals("FAILED", t.getStatus());
            assertEquals("QUEUE_WAIT_TIMEOUT", t.getErrorCode());
        } finally {
            config.updateAll(Map.of(ConfigKeys.TASK_QUEUE_MAX_WAIT_MS.getKey(), "1800000"));
        }
    }

    @org.junit.jupiter.api.DisplayName("v0.4：结构性无候选（渠道全停用）→ 排队任务立即 NO_AVAILABLE_CHANNEL，不让业务方干等")
    @Test
    void structuralDownFailsQueuedTaskFast() throws Exception {
        Channel ch = seedChannel("将停用上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-q4");
        seedGroup("q4.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-q4-aaaaaaaa", "10.00", null);
        // 先结构性下线（停用渠道）再提交：派发器对排队任务立即判死，不等轮询周期
        jdbc.update("UPDATE channel SET enabled=0 WHERE id=" + ch.getId());
        refreshSnapshot();
        String taskId = field(submit(sk, "{\"model\":\"q4.model\",\"prompt\":\"x\"}"), "taskId");

        GenerateTask t = awaitTerminal(taskId);
        assertEquals("FAILED", t.getStatus());
        assertEquals("NO_AVAILABLE_CHANNEL", t.getErrorCode());
    }

    @org.junit.jupiter.api.DisplayName("v0.4：换候选时下一账号也满 → 退回队列保留进度，重派后接着跑成")
    @Test
    void failoverFullCandidateRequeuesAndResumes() throws Exception {
        // A、B 同 Key 同账号共享容量 1：A 首打 5xx → 换候选 B 同账号无槽 → 退队；
        // 场景切到成功后重派 → A 第二次成功（进度没丢：两次尝试都计入）
        Channel a = seedChannel("同账号A", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-same");
        Channel b = seedChannel("同账号B", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-same");
        seedGroup("q5.model", "0.01", List.of(a, b), List.of("m1", "m2"));
        String sk = seedToken(1L, "sk-q5-aaaaaaaa", "10.00", null);
        setCapacityOf(a, "sk-same", 1);
        refreshSnapshot();

        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .inScenario("q5-a-flaky")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}"))
                .willSetStateTo("second"));
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .inScenario("q5-a-flaky")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/q5.png\"}]}")));

        String taskId = field(submit(sk, "{\"model\":\"q5.model\",\"prompt\":\"x\"}"), "taskId");

        GenerateTask t = awaitTerminal(taskId);
        assertEquals("SUCCEEDED", t.getStatus());
        assertEquals(2, t.getAttemptCount(), "两次真实尝试（5xx 一次 + 成功一次），进度跨退队保留");
        assertEquals(2, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))).size());
    }

    @org.junit.jupiter.api.DisplayName("v0.4：空闲优先——1 号饱和时跳过它选 2 号，绝不傻等")
    @Test
    void idleFirstSkipsSaturatedMember() throws Exception {
        Channel a = seedChannel("空闲A", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-idle-a");
        Channel b = seedChannel("空闲B", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-idle-b");
        ModelGroup g = seedGroup("q6.model", "0.01", List.of(a, b), List.of("m1", "m2"));
        g.setStrategy("IDLE_FIRST");
        groupMapper.updateById(g);
        String sk = seedToken(1L, "sk-q6-aaaaaaaa", "10.00", null);
        setCapacityOf(a, "sk-idle-a", 1);
        setCapacityOf(b, "sk-idle-b", 5);
        // 占满 1 号：插一条 RUNNING 行绑在渠道 A 上（=账号 A 在跑 1/1）
        jdbc.update("INSERT INTO generate_task (task_id, token_id, group_code, status, image_count, "
                        + "next_poll_time, expires_at, created_at, updated_at, channel_id) "
                        + "VALUES ('task_occ', 1, 'q6.model', 'RUNNING', 1, NOW(), NOW() + INTERVAL 15 MINUTE, NOW(), NOW(), "
                        + a.getId() + ")");
        refreshSnapshot();

        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-idle-a"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/idle-a.png\"}]}")));
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-idle-b"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/idle-b.png\"}]}")));

        String resp = submitAndAwait(sk, "{\"model\":\"q6.model\",\"prompt\":\"x\"}");
        assertEquals("SUCCEEDED", field(resp, "status"));
        // 1 号饱和 → 必须跳到 2 号（空闲优先的"跳满"）
        assertEquals(0, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-idle-a"))).size(), "饱和的 1 号不应被打到");
        assertEquals(1, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-idle-b"))).size(), "空闲的 2 号接单");
    }

    // ------------------------------------------------------------------ 第四轮交叉测试修复（2026-10-13）

    @org.junit.jupiter.api.DisplayName("修1：编辑渠道换 API Key——密文必须落库，账号键/容量随新 Key 走")
    @Test
    void channelUpdatePersistsRotatedApiKey() throws Exception {
        String admin = adminToken();
        String created = mvc.perform(post("/admin/channels").header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"name\":\"换Key渠道\",\"protocol\":\"OPENAI_IMAGES_SYNC\","
                                + "\"baseUrl\":\"" + WM.baseUrl() + "/v1\",\"apiKey\":\"sk-old-key-111\",\"maxConcurrency\":3}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = com.drawbridge.common.JsonUtil.readTree(created).path("data").asLong();

        // 换 Key：新密文必须写库（旧实现丢了 setApiKeyEnc——提示保存成功但上游仍用旧 Key），账号键按新 Key 计算
        String newKey = "sk-new-key-222";
        mvc.perform(put("/admin/channels/" + id).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"name\":\"换Key渠道\",\"protocol\":\"OPENAI_IMAGES_SYNC\","
                                + "\"baseUrl\":\"" + WM.baseUrl() + "/v1\",\"apiKey\":\"" + newKey
                                + "\",\"maxConcurrency\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountChanged").value(true));
        Channel ch = channelMapper.selectById(id);
        assertEquals(newKey, cipher.decrypt(ch.getApiKeyEnc()), "换 Key 后密文必须是新 Key");
        String newAccountKey = accountRegistry.computeKey(WM.baseUrl() + "/v1", newKey);
        assertEquals(newAccountKey, ch.getAccountKey(), "账号键必须按新 Key 重算");
        assertEquals(4, accountRegistry.capacityOf(newAccountKey), "容量随表单值落到新账号");

        // 不重传 Key 只改备注：存量密文不动
        mvc.perform(put("/admin/channels/" + id).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"name\":\"换Key渠道\",\"protocol\":\"OPENAI_IMAGES_SYNC\","
                                + "\"baseUrl\":\"" + WM.baseUrl() + "/v1\",\"maxConcurrency\":4,\"remark\":\"只改备注\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountChanged").value(false));
        assertEquals(newKey, cipher.decrypt(channelMapper.selectById(id).getApiKeyEnc()),
                "未重传 Key 不得动存量密文");
    }

    @org.junit.jupiter.api.DisplayName("修2：清扫②把提交链路崩溃残留退回队列，expires_at 恢复排队死线（旧实现 .apply 拼进 WHERE 永不生效）")
    @Test
    void sweeperRequeuesCrashedSubmitsWithQueueDeadlineRestored() {
        LocalDateTime now = LocalDateTime.now();
        // 崩溃残留：RUNNING、无任务号、无存档、停滞超 stale 窗口；expires_at 是未到的执行死线（证明被 SET 重置而非 WHERE 撞上）
        GenerateTask crashed = insertRunningCrashedTask(now.minusMinutes(11), now.plusMinutes(10));
        // 对照组：已存档请求体（可能已受理上游）→ 绝不自动重跑
        GenerateTask archived = insertRunningCrashedTask(now.minusMinutes(11), now.plusMinutes(10));
        jdbc.update("UPDATE generate_task SET upstream_request = '{\"model\":\"x\"}' WHERE id = " + archived.getId());

        stuckSweeper.sweep();

        GenerateTask requeued = taskMapper.selectById(crashed.getId());
        assertEquals("SUBMITTED", requeued.getStatus(), "崩溃残留必须退回队列");
        assertNull(requeued.getChannelId(), "退队必须清渠道绑定（槽位出账）");
        LocalDateTime deadline = requeued.getCreatedAt().plusSeconds(1800);
        assertTrue(Math.abs(java.time.Duration.between(requeued.getExpiresAt(), deadline).getSeconds()) <= 2,
                "expires_at 应恢复为 created_at+30min 排队死线，实际=" + requeued.getExpiresAt());
        assertEquals("RUNNING", taskMapper.selectById(archived.getId()).getStatus(),
                "已存档任务不自动重跑（防重复扣费），交超时终态");
    }

    private GenerateTask insertRunningCrashedTask(LocalDateTime createdAt, LocalDateTime expiresAt) {
        GenerateTask t = new GenerateTask();
        t.setTaskId("task_fix_" + System.nanoTime());
        t.setTokenId(1L);
        t.setGroupCode("fix.model");
        t.setStatus("RUNNING");
        t.setImageCount(1);
        t.setCreatedAt(createdAt);
        t.setUpdatedAt(createdAt);
        t.setNextPollTime(createdAt);
        t.setExpiresAt(expiresAt);
        taskMapper.insert(t);
        return t;
    }

    @org.junit.jupiter.api.DisplayName("修2b：换候选全满退队时清掉上次尝试的存档——重领后再崩溃仍在清扫②射程内")
    @Test
    void requeueClearsArchivedUpstreamRequest() throws Exception {
        Channel a = seedChannel("退队A", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-requeue");
        Channel b = seedChannel("退队B", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-requeue"); // 同账号
        seedGroup("rq.model", "0.01", List.of(a, b), List.of("m1", "m2"));
        String sk = seedToken(1L, "sk-rq-aaaaaaaaaa", "10.00", null);
        setCapacityOf(a, "sk-requeue", 1);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}")));

        submit(sk, "{\"model\":\"rq.model\",\"prompt\":\"x\"}");
        // A 尝试失败（重试类）→ B 未试但同账号无空槽 → 退队；退队必须清掉 A 尝试的存档
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            GenerateTask t = taskMapper.selectList(null).getFirst();
            assertEquals("SUBMITTED", t.getStatus(), "应退队保留进度而非失败");
            assertEquals(1, t.getAttemptCount().intValue());
            assertNull(t.getUpstreamRequest(), "退队时上次尝试的存档必须清掉");
            assertNull(t.getChannelId());
        });
    }

    @org.junit.jupiter.api.DisplayName("修3：组间公平轮——共享账号容量2时，A、B 两组各领一条（旧实现 A 组抽干两条，B 组饿死）")
    @Test
    void crossGroupFairSharingOfAccountSlots() {
        Channel a = seedChannel("公平A", "OPENAI_ASYNC_TASK", WM.baseUrl() + "/v1", "sk-fair",
                Map.of("flavor", "nezha"));
        seedGroup("fa.model", "0.01", List.of(a), List.of("m"));
        seedGroup("fb.model", "0.01", List.of(a), List.of("m")); // 同渠道同账号挂两个分组
        setCapacityOf(a, "sk-fair", 2);
        refreshSnapshot();
        // 异步上游受理后一直 processing：领取的槽位在断言窗口内不释放，公平分布可静态断言
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(202).withBody("{\"id\":\"job_fair_{{randomInt lower=1 upper=999999}}\"}")
                        .withTransformers("response-template")));
        WM.stubFor(WireMock.get(WireMock.urlMatching("/v1/images/jobs/job_fair_.*"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"processing\"}")));

        GenerateTask ta1 = insertSubmittedTask("fa.model", 0, null);
        GenerateTask ta2 = insertSubmittedTask("fa.model", 0, null);
        GenerateTask tb1 = insertSubmittedTask("fb.model", 0, null);
        // helper 不写 biz_request：领取后 SubmitPayload.parse(null) 会在链路外抛异常触发回退清渠道，断言前必须补上。
        // 补写期间用未来 next_poll_time 挡住后台派发器抢跑（抢跑→parse 失败→回退清渠道=本测试随机红），放行后手动扫描
        jdbc.update("UPDATE generate_task SET biz_request = '{\"model\":\"fa.model\",\"prompt\":\"x\"}', "
                + "next_poll_time = NOW() + INTERVAL 1 HOUR "
                + "WHERE id IN (" + ta1.getId() + "," + ta2.getId() + "," + tb1.getId() + ")");

        jdbc.update("UPDATE generate_task SET next_poll_time = NOW() "
                + "WHERE id IN (" + ta1.getId() + "," + ta2.getId() + "," + tb1.getId() + ")");
        submitExecutor.scanDue();

        // 公平轮：第 1 轮 A 领 TA1、B 领 TB1，第 2 轮 A 领 TA2（槽位已满留队）。
        // 旧实现：A 组 while 抽干 TA1+TA2，TB1 永远等不到
        assertNotNull(taskMapper.selectById(ta1.getId()).getChannelId(), "TA1 应领取");
        assertNotNull(taskMapper.selectById(tb1.getId()).getChannelId(),
                "公平轮：B 分组必须同轮拿到共享账号的槽位");
        assertNull(taskMapper.selectById(ta2.getId()).getChannelId(),
                "容量 2 已满，TA2 应留队等待而非挤掉 B 组槽位");
    }

    @org.junit.jupiter.api.DisplayName("修9：FAILOVER 首选账号被暂停（容量0）→ 跳过首选由健康备用接单，不再卡死整组")
    @Test
    void failoverSkipsPausedPreferredAccount() throws Exception {
        Channel c1 = seedChannel("首选", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-paused");
        Channel c2 = seedChannel("备用", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-healthy");
        seedGroup("fp.model", "0.01", List.of(c1, c2), List.of("m", "m"));
        String sk = seedToken(1L, "sk-fp-aaaaaaaaaa", "10.00", null);
        setCapacityOf(c1, "sk-paused", 0); // 管理员暂停首选
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-paused"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"url\":\"https://cdn.result/never.png\"}]}")));
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-healthy"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"url\":\"https://cdn.result/ok.png\"}]}")));

        String resp = submitAndAwait(sk, "{\"model\":\"fp.model\",\"prompt\":\"x\"}");
        assertEquals("SUCCEEDED", field(resp, "status"));
        GenerateTask t = taskMapper.selectList(null).getFirst();
        assertEquals(c2.getId(), t.getChannelId(), "暂停的首选必须被跳过（旧实现返回 null 整组卡死）");
        assertEquals(0, WM.findAll(WireMock.postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-paused"))).size(), "暂停账号不应被打到");
    }

    @org.junit.jupiter.api.DisplayName("修8：单渠道 429 冷却——任务退队等恢复而非直接 UPSTREAM_ALL_FAILED，冷却过后重试成功")
    @Test
    void rateLimitCooldownRequeuesInsteadOfFailing() throws Exception {
        config.updateAll(Map.of(ConfigKeys.RATE_LIMIT_COOLDOWN_MS.getKey(), "1000"));
        Channel ch = seedChannel("限速上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-429");
        seedGroup("rl.model", "0.01", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-rl-aaaaaaaaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":{\"message\":\"rate\"}}")));

        String taskId = field(submit(sk, "{\"model\":\"rl.model\",\"prompt\":\"x\"}"), "taskId");
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            GenerateTask t = taskByTaskId(taskId);
            assertEquals("SUBMITTED", t.getStatus(), "429 冷却应退队等待而非失败（旧实现直接 UPSTREAM_ALL_FAILED）");
            assertNull(t.getChannelId());
        });
        // 冷却（1s）+ 退队退避（3s）过后派发器重试 → 200
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"url\":\"https://cdn.result/rl.png\"}]}")));
        GenerateTask t = awaitTerminal(taskId);
        assertEquals("SUCCEEDED", t.getStatus());
        assertTrue(t.getAttemptCount() >= 2, "冷却后应重试成功: attempts=" + t.getAttemptCount());
        config.updateAll(Map.of(ConfigKeys.RATE_LIMIT_COOLDOWN_MS.getKey(), "30000"));
    }

    @org.junit.jupiter.api.DisplayName("修5：账号级停用幂等不重复建批；单渠道启用从批次摘除，批内清空即关批")
    @Test
    void accountDisableIdempotentAndEnableSettlesBatch() {
        Channel a = seedChannel("批A", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-batch");
        Channel b = seedChannel("批B", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-batch"); // 同账号
        ModelGroup g = seedGroup("bt.model", "0.01", List.of(a, b), List.of("m1", "m2"));
        String accountKey = accountRegistry.computeKey(WM.baseUrl() + "/v1", "sk-batch");

        channelOps.autoDisableAccount(accountKey, "没米");
        channelOps.autoDisableAccount(accountKey, "没米"); // 模拟并发第二单再触发
        assertEquals(1, disableBatchMapper.selectList(null).size(),
                "enabled=0 幂等闸：第二次调用不得重复建批（旧实现只改 status，并发 402 各建一批）");
        assertEquals(0, channelMapper.selectById(a.getId()).getEnabled().intValue());
        assertEquals(4, channelMapper.selectById(a.getId()).getStatus().intValue());

        // 单渠道启用 A：从批次摘除 A（其成员一并摘除），批内还剩 B → 批次仍开放
        channelOps.enable(a.getId());
        DisableBatch batch = disableBatchMapper.selectList(null).getFirst();
        assertNull(batch.getRestoredAt(), "批内还有 B，批次不得提前关闭");
        assertEquals(List.of(b.getId()), com.drawbridge.common.JsonUtil.readTree(batch.getChannelIds())
                .valueStream().map(n -> n.asLong()).toList(), "批次渠道清单应只剩 B");
        assertEquals(1, memberByModel(g, a, "m1").getEnabled().intValue(), "A 的联动成员随渠道启用恢复");
        assertEquals(0, memberByModel(g, b, "m2").getEnabled().intValue(), "B 的成员保持停用");

        // B 也启用 → 批内清空 → 批次关闭（恢复按钮消失）
        channelOps.enable(b.getId());
        batch = disableBatchMapper.selectList(null).getFirst();
        assertNotNull(batch.getRestoredAt(), "批内渠道清空必须关闭批次");
        assertEquals(1, memberByModel(g, b, "m2").getEnabled().intValue());
    }

    @org.junit.jupiter.api.DisplayName("修6：渠道启用只复活联动停用的成员——管理员手动停的（disable_source=admin）不被顺手拉起")
    @Test
    void enableRestoresOnlyCascadeDisabledMembers() throws Exception {
        Channel ch = seedChannel("出处上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-src");
        ModelGroup g = seedGroup("src.model", "0.01", List.of(ch, ch), List.of("m1", "m2"));
        GroupMember manual = memberByModel(g, ch, "m2");
        String admin = adminToken();
        mvc.perform(put("/admin/groups/" + g.getId() + "/members/" + manual.getId())
                        .header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"enabled\":0}"))
                .andExpect(status().isOk());
        assertEquals("admin", memberMapper.selectById(manual.getId()).getDisableSource(),
                "手动停用必须记 admin 出处");

        // 渠道手动停用 → 联动停 m1（m2 已停，不再动）
        channelOps.disable(ch.getId(), "维护");
        assertEquals("cascade", memberByModel(g, ch, "m1").getDisableSource(), "联动停用必须记 cascade 出处");
        assertEquals("admin", memberByModel(g, ch, "m2").getDisableSource(), "手动停用出处不得被联动覆盖");

        // 启用渠道：只复活 m1
        channelOps.enable(ch.getId());
        assertEquals(1, memberByModel(g, ch, "m1").getEnabled().intValue());
        assertNull(memberByModel(g, ch, "m1").getDisableSource(), "恢复后出处应清掉");
        assertEquals(0, memberByModel(g, ch, "m2").getEnabled().intValue(),
                "管理员手动停用的成员不得被渠道启用复活");
    }

    private GroupMember memberByModel(ModelGroup g, Channel ch, String model) {
        return memberMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<GroupMember>lambdaQuery()
                        .eq(GroupMember::getGroupId, g.getId())
                        .eq(GroupMember::getChannelId, ch.getId())
                        .eq(GroupMember::getUpstreamModel, model))
                .getFirst();
    }

    @org.junit.jupiter.api.DisplayName("修7：分组有在途任务时拒绝改名——旧编码任务不能被孤儿化；任务终态后放行")
    @Test
    void renameBlockedWhileTasksInflight() throws Exception {
        Channel ch = seedChannel("改名上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-rn");
        ModelGroup g = seedGroup("rn.model", "0.01", List.of(ch), List.of("m"));
        String admin = adminToken();

        insertSubmittedTask("rn.model", 0, null);
        mvc.perform(put("/admin/groups/" + g.getId()).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"groupCode\":\"rn2.model\",\"strategy\":\"FAILOVER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("在途任务")));

        jdbc.update("UPDATE generate_task SET status='FAILED', error_code='TEST' WHERE group_code='rn.model'");
        mvc.perform(put("/admin/groups/" + g.getId()).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"groupCode\":\"rn2.model\",\"strategy\":\"FAILOVER\"}"))
                .andExpect(status().isOk());
        assertEquals("rn2.model", groupMapper.selectById(g.getId()).getGroupCode());
    }

    // ------------------------------------------------------------------ 第二轮交叉测试修复（2026-10-13 下午）

    @org.junit.jupiter.api.DisplayName("新1：容量写入口径——同 Key 新建渠道不动已有池子；换到已有账号不覆盖；新账号才带表单值")
    @Test
    void capacityWriteOnlyTouchesNewAccounts() throws Exception {
        String admin = adminToken();
        String base = WM.baseUrl() + "/v1";
        java.util.function.BiFunction<String, Integer, Long> create = (key, cap) -> {
            try {
                String body = "{\"name\":\"容量渠道\",\"protocol\":\"OPENAI_IMAGES_SYNC\","
                        + "\"baseUrl\":\"" + base + "\",\"apiKey\":\"" + key + "\",\"maxConcurrency\":" + cap + "}";
                String resp = mvc.perform(post("/admin/channels").header("X-Admin-Token", admin)
                                .header("Content-Type", "application/json").content(body))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                return com.drawbridge.common.JsonUtil.readTree(resp).path("data").asLong();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        long aId = create.apply("sk-cap-prot", 20);
        String protKey = accountRegistry.computeKey(base, "sk-cap-prot");
        assertEquals(20, accountRegistry.capacityOf(protKey));

        // 同 Key 再挂一个渠道（表单默认 5）：已有账号池子必须原封不动
        create.apply("sk-cap-prot", 5);
        assertEquals(20, accountRegistry.capacityOf(protKey), "同 Key 新建渠道不得把已有账号容量改回表单默认值");

        // 另一个已有账号（容量 7）：编辑渠道换到它，不得被表单旧值覆盖
        create.apply("sk-other-key", 7);
        String otherKey = accountRegistry.computeKey(base, "sk-other-key");
        long bId = create.apply("sk-cap-prot2", 5); // 第三个渠道准备换 Key
        mvc.perform(put("/admin/channels/" + bId).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"name\":\"容量换绑\",\"protocol\":\"OPENAI_IMAGES_SYNC\","
                                + "\"baseUrl\":\"" + base + "\",\"apiKey\":\"sk-other-key\",\"maxConcurrency\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountChanged").value(true));
        assertEquals(7, accountRegistry.capacityOf(otherKey), "换绑到已有账号不得覆盖该账号容量");

        // 换到全新账号：表单值带走初始化
        mvc.perform(put("/admin/channels/" + bId).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"name\":\"容量换绑\",\"protocol\":\"OPENAI_IMAGES_SYNC\","
                                + "\"baseUrl\":\"" + base + "\",\"apiKey\":\"sk-brand-new\",\"maxConcurrency\":9}"))
                .andExpect(status().isOk());
        assertEquals(9, accountRegistry.capacityOf(accountRegistry.computeKey(base, "sk-brand-new")));

        // 账号没变：管理员正经调自己的池子 → 生效
        mvc.perform(put("/admin/channels/" + bId).header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"name\":\"容量换绑\",\"protocol\":\"OPENAI_IMAGES_SYNC\","
                                + "\"baseUrl\":\"" + base + "\",\"apiKey\":\"sk-brand-new\",\"maxConcurrency\":12}"))
                .andExpect(status().isOk());
        assertEquals(12, accountRegistry.capacityOf(accountRegistry.computeKey(base, "sk-brand-new")),
                "同账号编辑容量必须生效");
    }

    @org.junit.jupiter.api.DisplayName("新2：整批恢复只拉起仍处于自动停用态的渠道/成员——批次期间手动停的不复活")
    @Test
    void restoreBatchSkipsManuallyDisabled() throws Exception {
        Channel a = seedChannel("批A", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-batch2");
        Channel b = seedChannel("批B", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-batch2");
        ModelGroup g = seedGroup("rb.model", "0.01", List.of(a, b), List.of("m1", "m2"));
        String accountKey = accountRegistry.computeKey(WM.baseUrl() + "/v1", "sk-batch2");

        channelOps.autoDisableAccount(accountKey, "没米");
        com.drawbridge.domain.entity.DisableBatch batch =
                disableBatchMapper.selectList(null).getFirst();

        // 批次建好后：管理员手动停 A（status=0）、手动停 m2（source=admin）
        channelOps.disable(a.getId(), "管理员手动停");
        GroupMember m2 = memberByModel(g, b, "m2");
        mvc.perform(put("/admin/groups/" + g.getId() + "/members/" + m2.getId())
                        .header("X-Admin-Token", adminToken())
                        .header("Content-Type", "application/json")
                        .content("{\"enabled\":0}"))
                .andExpect(status().isOk());

        channelOps.restoreBatch(batch.getId());

        Channel aAfter = channelMapper.selectById(a.getId());
        assertEquals(0, aAfter.getEnabled().intValue(), "批次期间手动停用的渠道不得被整批恢复拉起");
        assertEquals(0, aAfter.getStatus().intValue());
        Channel bAfter = channelMapper.selectById(b.getId());
        assertEquals(1, bAfter.getEnabled().intValue(), "仍处于余额停用的渠道应被恢复");
        assertEquals(1, memberByModel(g, a, "m1").getEnabled().intValue());
        GroupMember m2After = memberMapper.selectById(m2.getId());
        assertEquals(0, m2After.getEnabled().intValue(), "批次期间手动停用的成员不得被整批恢复拉起");
        assertEquals("admin", m2After.getDisableSource());
        assertNotNull(disableBatchMapper.selectById(batch.getId()).getRestoredAt());
    }

    @org.junit.jupiter.api.DisplayName("未净1：A 刚 5xx、B 正冷却——nextHop 识别'未试但暂时不可用'退队，不再误判 UPSTREAM_ALL_FAILED")
    @Test
    void untriedCoolingCandidateCausesRequeueNotFail() throws Exception {
        Channel a = seedChannel("五零零", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-c1");
        Channel b = seedChannel("冷却中", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-c2");
        seedGroup("uc.model", "0.01", List.of(a, b), List.of("m1", "m2"));
        String sk = seedToken(1L, "sk-uc-aaaaaaaaaa", "10.00", null);
        refreshSnapshot();
        cooldownRegistry.cool(b.getId()); // B 进入 429 冷却（结构可用，暂时不可用）
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-c1"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}")));
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", containing("sk-c2"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"url\":\"https://cdn.result/x.png\"}]}")));

        String taskId = field(submit(sk, "{\"model\":\"uc.model\",\"prompt\":\"x\"}"), "taskId");
        // A 尝试 5xx（未熔断）→ 候选列表只剩试过的 A → 旧实现直接 UPSTREAM_ALL_FAILED；现在应识别 B 在冷却 → 退队
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            GenerateTask t = taskByTaskId(taskId);
            assertEquals("SUBMITTED", t.getStatus(), "有未试但暂时不可用的候选应退队等待而非失败");
            assertNull(t.getChannelId());
        });
    }

    @org.junit.jupiter.api.DisplayName("未净2：分组停用后排队任务立即结构性失败，不等 30 分钟排队死线")
    @Test
    void disabledGroupFailsQueuedImmediately() throws Exception {
        Channel ch = seedChannel("停组上游", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-dg");
        ModelGroup g = seedGroup("dg.model", "0.01", List.of(ch), List.of("m"));
        String admin = adminToken();
        GenerateTask queued = insertSubmittedTask("dg.model", 0, null);
        jdbc.update("UPDATE generate_task SET biz_request = '{\"model\":\"dg.model\",\"prompt\":\"x\"}', "
                + "next_poll_time = NOW() + INTERVAL 1 HOUR WHERE id = " + queued.getId());

        mvc.perform(post("/admin/groups/" + g.getId() + "/disable").header("X-Admin-Token", admin))
                .andExpect(status().isOk());
        refreshSnapshot();
        // 未来 next_poll_time 挡住了后台派发器抢跑；现在放行并直扫一轮
        jdbc.update("UPDATE generate_task SET next_poll_time = NOW() WHERE id = " + queued.getId());
        submitExecutor.scanDue();

        GenerateTask t = taskMapper.selectById(queued.getId());
        assertEquals("FAILED", t.getStatus(), "停用分组的排队任务应立即失败: status=" + t.getStatus()
                + " nextPoll=" + t.getNextPollTime() + " attempts=" + t.getAttemptCount());
        assertEquals("NO_AVAILABLE_CHANNEL", t.getErrorCode());
    }

    // ------------------------------------------------------------------ apiyi（gpt-image 系 + nano 系）

    private static final byte[] REF_PNG = {(byte) 0x89, 'P', 'N', 'G', 0xD, 0xA, 0x1A, 0xA, 0, 1};

    @org.junit.jupiter.api.DisplayName("apiyi image 系：openai_edits 渠道带参考图 → 网关代下载走 multipart edits，同步出图")
    @Test
    void apiyiEditsMultipartChain() throws Exception {
        Channel ch = seedChannel("apiyi同步", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1",
                "sk-apiyi", Map.of("refImageStyle", "openai_edits", "timeoutMs", 300000));
        seedGroup("img25", "0.02", List.of(ch), List.of("gpt-image-2.5-flare"));
        String sk = seedToken(1L, "sk-apiyi-img-aaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.get(urlEqualTo("/refs/a.png"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "image/png").withBody(REF_PNG)));
        WM.stubFor(WireMock.post(urlEqualTo("/v1/images/edits"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}")));

        String resp = submitAndAwait(sk, "{\"model\":\"img25\",\"prompt\":\"改图\",\"aspectRatio\":\"16:9\",\"resolution\":\"2k\","
                + "\"images\":[\"" + WM.baseUrl() + "/refs/a.png\"]}");

        assertEquals("SUCCEEDED", field(resp, "status"));
        assertEquals("data:image/png;base64,aGVsbG8=",
                com.drawbridge.common.JsonUtil.readTree(resp).path("data").path("result").path("urls").get(0).asText());
        GenerateTask task = taskByTaskId(field(resp, "taskId"));
        assertEquals(WM.baseUrl() + "/v1/images/edits", task.getUpstreamUrl(), "带参考图应切 edits 端点");
        // multipart 形状：文件字段 image[]（代下载后上传）+ 表单参数
        String upstreamBody = WM.findAll(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/images/edits")))
                .get(0).getBodyAsString();
        assertTrue(upstreamBody.contains("name=\"image[]\"; filename=\"a.png\""), upstreamBody);
        assertTrue(upstreamBody.contains("name=\"model\"\r\n\r\ngpt-image-2.5-flare"), upstreamBody);
        assertTrue(upstreamBody.contains("name=\"size\"\r\n\r\n2048x1152"), upstreamBody);
        // 渠道级超时覆盖进 cmd：存档路径为 edits、任务成功即代表整个链路按新配置跑通
    }

    @org.junit.jupiter.api.DisplayName("apiyi nano 系：Gemini 原生 generateContent，parts 混排取最后一张")
    @Test
    void nanoChainTakesLastImage() throws Exception {
        Channel ch = seedChannel("apiyi nano", "GEMINI_NATIVE", WM.baseUrl(), "sk-nano",
                Map.of("timeoutMs", 300000));
        seedGroup("nano-pro", "0.09", List.of(ch), List.of("gemini-3-pro-image-preview"));
        String sk = seedToken(1L, "sk-nano-chain-aaa", "10.00", null);
        refreshSnapshot();
        String url = "/v1beta/models/gemini-3-pro-image-preview:generateContent";
        WM.stubFor(WireMock.post(urlEqualTo(url))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"candidates":[{"content":{"parts":[
                            {"text":"中间说明"},
                            {"inlineData":{"mimeType":"image/png","data":"QUFBQQ=="}},
                            {"inlineData":{"mimeType":"image/jpeg","data":"QkFBQQ=="}}
                        ]},"finishReason":"STOP"}]}
                        """)));

        String resp = submitAndAwait(sk, "{\"model\":\"nano-pro\",\"prompt\":\"海报\",\"resolution\":\"2k\",\"aspectRatio\":\"16:9\"}");

        assertEquals("SUCCEEDED", field(resp, "status"));
        // 最后一张 = 终稿（思考中间稿丢弃），mimeType 以响应为准
        assertEquals("data:image/jpeg;base64,QkFBQQ==",
                com.drawbridge.common.JsonUtil.readTree(resp).path("data").path("result").path("urls").get(0).asText());
        GenerateTask task = taskByTaskId(field(resp, "taskId"));
        assertEquals(WM.baseUrl() + url, task.getUpstreamUrl());
        String upstreamBody = task.getUpstreamRequest();
        assertTrue(upstreamBody.contains("\"responseModalities\":[\"IMAGE\"]"), upstreamBody);
        assertTrue(upstreamBody.contains("\"imageSize\":\"2K\""), upstreamBody);
        assertTrue(upstreamBody.contains("\"aspectRatio\":\"16:9\""), upstreamBody);
    }

    @org.junit.jupiter.api.DisplayName("nano 系审核拦截：HTTP 200 无图段 → CONTENT_REJECTED 终态")
    @Test
    void nanoContentBlockedTerminal() throws Exception {
        Channel ch = seedChannel("nano拦截", "GEMINI_NATIVE", WM.baseUrl(), "sk-nano-b");
        seedGroup("nano-b", "0.09", List.of(ch), List.of("gemini-3-pro-image-preview"));
        String sk = seedToken(1L, "sk-nano-block-aaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.post(urlEqualTo("/v1beta/models/gemini-3-pro-image-preview:generateContent"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"candidates\":[{\"content\":{\"parts\":null},\"finishReason\":\"SAFETY\"}]}")));

        String resp = submitAndAwait(sk, "{\"model\":\"nano-b\",\"prompt\":\"违规\"}");

        assertEquals("FAILED", field(resp, "status"));
        assertEquals("CONTENT_REJECTED", field(resp, "error.code"));
        assertTrue(field(resp, "error.message").contains("SAFETY"), field(resp, "error.message"));
    }

    @org.junit.jupiter.api.DisplayName("参考图拉取失败：任务失败但渠道不受惩罚（不喂熔断不停渠道）")
    @Test
    void refImageFetchFailureSavesTheChannel() throws Exception {
        Channel ch = seedChannel("apiyi图生图", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1",
                "sk-ref-fail", Map.of("refImageStyle", "openai_edits"));
        seedGroup("reffail", "0.02", List.of(ch), List.of("m"));
        String sk = seedToken(1L, "sk-ref-fail-aaaa", "10.00", null);
        refreshSnapshot();
        WM.stubFor(WireMock.get(urlEqualTo("/missing.png"))
                .willReturn(aResponse().withStatus(404)));

        String resp = submitAndAwait(sk, "{\"model\":\"reffail\",\"prompt\":\"x\",\"images\":[\""
                + WM.baseUrl() + "/missing.png\"]}");

        assertEquals("FAILED", field(resp, "status"));
        assertTrue(field(resp, "error.message").contains("参考图下载失败"), field(resp, "error.message"));
        // 锅在图床：渠道不喂熔断不停用，健康状态原样
        Channel after = channelMapper.selectById(ch.getId());
        assertEquals(1, after.getEnabled());
        assertEquals(1, after.getStatus());
    }

    @org.junit.jupiter.api.DisplayName("apiyi 同步模型按协议族过滤：image 系不混 nano/gemini，nano 系不混聊天模型")
    @Test
    void syncModelsFiltersByProtocolFamily() throws Exception {
        Channel img = seedChannel("apiyi同步", "OPENAI_IMAGES_SYNC", WM.baseUrl() + "/v1", "sk-f1");
        Channel nano = seedChannel("apiyi nano", "GEMINI_NATIVE", WM.baseUrl(), "sk-f2");
        String admin = adminToken();
        refreshSnapshot();

        WM.stubFor(WireMock.get(urlEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"data":[{"id":"gpt-image-2.5-flare"},{"id":"gpt-image-2-all"},
                                 {"id":"gemini-3-pro-image-preview"},{"id":"gpt-5.4-mini"},{"id":"text-embedding-3"}]}
                        """)));
        WM.stubFor(WireMock.get(urlEqualTo("/v1beta/models"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"models":[{"name":"models/gemini-3-pro-image-preview"},{"name":"models/gemini-nano-banana-2.1"},
                                   {"name":"models/gemini-3.6-flash"}]}
                        """)));

        String r1 = mvc.perform(post("/admin/channels/" + img.getId() + "/sync-models")
                        .header("X-Admin-Token", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var d1 = com.drawbridge.common.JsonUtil.readTree(r1).path("data");
        assertEquals(2, d1.path("count").asInt(), "image 渠道只收 gpt-image 两款");
        assertEquals(3, d1.path("filtered").asInt(), "gemini 生图/聊天/向量化被过滤");

        String r2 = mvc.perform(post("/admin/channels/" + nano.getId() + "/sync-models")
                        .header("X-Admin-Token", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var d2 = com.drawbridge.common.JsonUtil.readTree(r2).path("data");
        assertEquals(2, d2.path("count").asInt(), "nano 渠道只收 gemini 生图两款");
        assertEquals(1, d2.path("filtered").asInt(), "gemini 聊天模型被过滤");

        // model_code 按字典序：'-'(0x2D) < '.'(0x2E)，gpt-image-2-all 排在 gpt-image-2.5-flare 前
        List<String> imgCodes = jdbc.queryForList(
                "SELECT model_code FROM upstream_model WHERE channel_id = " + img.getId() + " ORDER BY model_code",
                String.class);
        assertEquals(List.of("gpt-image-2-all", "gpt-image-2.5-flare"), imgCodes);
        List<String> nanoCodes = jdbc.queryForList(
                "SELECT model_code FROM upstream_model WHERE channel_id = " + nano.getId() + " ORDER BY model_code",
                String.class);
        assertEquals(List.of("gemini-3-pro-image-preview", "gemini-nano-banana-2.1"), nanoCodes);
    }

    @org.junit.jupiter.api.DisplayName("裸域名 SPA fallback：/models 回 200 HTML 不算命中，回退 /v1/models；probe 不假阳性")
    @Test
    void spaHtmlFallbackDoesNotFoolModelsProbe() throws Exception {
        // apiyi 实测：裸域名 /models 返回 200 + HTML 官网页（SPA fallback），必须继续回退
        Channel ch = seedChannel("apiyi裸域", "OPENAI_IMAGES_SYNC", WM.baseUrl(), "sk-spa");
        String admin = adminToken();
        refreshSnapshot();
        WM.stubFor(WireMock.get(urlEqualTo("/models"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<!doctype html><html><body>官网首页</body></html>")));
        WM.stubFor(WireMock.get(urlEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(401)
                        .withBody("{\"error\":{\"message\":\"Invalid token\",\"type\":\"shell_api_error\"}}")));

        // sync-models：不炸非 JSON，而是把 /v1/models 的 401 报出来
        mvc.perform(post("/admin/channels/" + ch.getId() + "/sync-models").header("X-Admin-Token", admin))
                .andExpect(status().isBadRequest());
        // probe：不是"连通正常"假阳性，而是 Key 无效
        String r = mvc.perform(post("/admin/channels/probe")
                        .header("X-Admin-Token", admin)
                        .header("Content-Type", "application/json")
                        .content("{\"protocol\":\"OPENAI_IMAGES_SYNC\",\"baseUrl\":\"" + WM.baseUrl()
                                + "\",\"apiKey\":\"sk-spa\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(com.drawbridge.common.JsonUtil.readTree(r).path("data").path("result").asText()
                .contains("Key 无效"), r);
    }
}
