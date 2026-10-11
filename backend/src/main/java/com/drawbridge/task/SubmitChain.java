package com.drawbridge.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.adapter.ImageProvider;
import com.drawbridge.adapter.ProviderRegistry;
import com.drawbridge.adapter.UpstreamCallException;
import com.drawbridge.adapter.UpstreamErrorKind;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.entity.TaskAttempt;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.domain.mapper.TaskAttemptMapper;
import com.drawbridge.health.ChannelHealthTracker;
import com.drawbridge.health.ChannelOpsService;
import com.drawbridge.health.CooldownRegistry;
import com.drawbridge.health.ConcurrencyGuard;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import com.drawbridge.route.RouteEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 提交链路（虚拟线程上执行）：路由候选 → 逐个上游尝试 → 受理/终态。
 * 规则：
 * - 只有"渠道的病"（超时/5xx/429/没米/Key失效）才换下一候选；提示词/内容/参数类错误直接终态
 * - 没米/Key 失效 → 渠道自动停用 + 告警（ChannelOpsService）
 * - 429 → 渠道短冷却；网络/5xx/未知 → 熔断计数
 * - 候选耗尽 → UPSTREAM_ALL_FAILED / NO_AVAILABLE_CHANNEL，退款
 * - 上游受理成功后绝不重发（重发可能重复扣费）
 * - 停机强制中断不是渠道病：已发过请求 → SYSTEM_SHUTDOWN 终态；没发过 → 回退重跑
 * - 2xx 但响应不可解析（BAD_RESPONSE）→ 终态不转移（上游可能已出图，转移即重复扣费）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmitChain {

    private final GenerateTaskMapper taskMapper;
    private final TaskAttemptMapper attemptMapper;
    private final RouteEngine routeEngine;
    private final ProviderRegistry registry;
    private final ChannelConfigService configService;
    private final SystemConfigService config;
    private final ChannelHealthTracker health;
    private final CooldownRegistry cooldown;
    private final ConcurrencyGuard guard;
    private final ChannelOpsService channelOps;
    private final TaskFinisher finisher;

    public void run(Long taskId) {
        GenerateTask task = taskMapper.selectById(taskId);
        if (task == null || !TaskStatus.RUNNING.name().equals(task.getStatus())) {
            return;
        }
        SubmitPayload payload = SubmitPayload.parse(task.getBizRequest());
        int n = task.getImageCount() == null ? 1 : task.getImageCount();

        List<RouteEngine.Candidate> candidates = routeEngine.candidatesFor(task.getGroupCode());
        if (candidates.isEmpty()) {
            finisher.fail(taskId, "NO_AVAILABLE_CHANNEL", "该模型暂无可用上游渠道");
            return;
        }

        long deadline = System.currentTimeMillis() + config.getLong(ConfigKeys.ROUTE_TIME_BUDGET_MS);
        int maxAttempts = config.getInt(ConfigKeys.ROUTE_MAX_CHANNEL_ATTEMPTS);
        int submitTimeout = (int) config.getLong(ConfigKeys.SUBMIT_TIMEOUT_MS);
        int tried = 0;
        UpstreamCallException lastError = null;
        Channel lastErrorChannel = null;
        // 本次运行是否已向上游发出过请求：停机中断时决定"诚实终态"还是"安全回退"的判据
        boolean requestSent = false;

        for (RouteEngine.Candidate candidate : candidates) {
            if (tried >= maxAttempts || System.currentTimeMillis() > deadline) {
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                // 停机强制中断：不再向任何渠道发请求（还没碰过上游 → 可安全回退）
                throw new SubmissionAbortedException("停机中断，本次提交在发请求前终止");
            }
            Channel channel = candidate.channel();
            if (!guard.tryAcquire(channel.getId(), channel.getMaxConcurrency(),
                    config.getLong(ConfigKeys.ROUTE_PERMIT_WAIT_MS))) {
                log.info("渠道并发闸等待超时，跳过: taskId={}, channelId={}", task.getTaskId(), channel.getId());
                continue;
            }
            tried++;
            long t0 = System.currentTimeMillis();
            String submitUrl = null;
            boolean upstreamHit = false;
            ImageProvider.SubmitOutcome currentOutcome = null;
            try {
                ImageProvider provider = registry.find(channel.getProtocol()).orElse(null);
                if (provider == null) {
                    recordAttempt(task, channel, candidate.member().getUpstreamModel(),
                            "NO_PROVIDER", null, "该协议族无适配器", costMs(t0), null);
                    lastError = new UpstreamCallException(UpstreamErrorKind.UNKNOWN,
                            "渠道协议无适配器: " + channel.getProtocol(), null);
                    lastErrorChannel = channel;
                    continue;
                }
                ChannelConfigService.ChannelRuntime rt = configService.runtimeOf(channel);
                ImageProvider.SubmitCommand cmd = new ImageProvider.SubmitCommand(
                        candidate.member().getUpstreamModel(), payload.prompt(), n,
                        payload.resolution(), payload.aspectRatio(), payload.legacySize(),
                        payload.images(), payload.options(), submitTimeout);
                submitUrl = provider.submitUrl(cmd, rt);
                String requestJson = com.drawbridge.common.JsonUtil.truncateBytes(
                        provider.buildRequestJson(cmd, rt),
                        config.getInt(ConfigKeys.RETENTION_BODY_MAX_BYTES));
                // 请求体与命中地址先落档：失败排障时能看到"我们发了什么、发到了哪"；
                // 同时是兜底清扫的重跑判据（已存档=已到达发送点，不允许自动重跑）
                archiveUpstreamRequestBody(taskId, submitUrl, requestJson);

                requestSent = true;
                ImageProvider.SubmitOutcome outcome = provider.submit(cmd, rt);
                // 跨过这一行，上游已受理：此后任何异常都不允许换渠道重发（防重复出图重复扣费）
                upstreamHit = true;
                currentOutcome = outcome;
                try {
                    recordAttempt(task, channel, candidate.member().getUpstreamModel(),
                            null, null, null, costMs(t0), submitUrl);
                } catch (Exception ae) {
                    log.warn("尝试流水写入失败（不影响受理，任务时间线会缺一条）: taskId={}", task.getTaskId(), ae);
                }
                health.recordSuccess(channel.getId());
                // 收尾写库（存档+终态/任务号）带内部重试；全失败向上抛，由 upstreamHit 分支兜住（带上游凭证落日志）
                persistOutcome(task, taskId, outcome, requestJson, channel);
                log.info("上游受理成功: taskId={}, channel={}, outcome={}",
                        task.getTaskId(), channel.getId(), describeOutcome(outcome));
                return;
            } catch (UpstreamCallException e) {
                if (e.getKind() == UpstreamErrorKind.SHUTDOWN_INTERRUPT) {
                    // 停机中断不是渠道病：不喂熔断不停用。已发过请求 → 诚实终态（上游可能已受理，
                    // 回退重跑会重复扣费）；没发过 → 回退 SUBMITTED 交重启后重跑。
                    recordAttempt(task, channel, candidate.member().getUpstreamModel(),
                            e.getKind().name(), e.getHttpStatus(), e.getMessage(), costMs(t0), submitUrl);
                    if (requestSent) {
                        finisher.fail(taskId, "SYSTEM_SHUTDOWN",
                                "发版停机中断提交（上游可能已受理），请重新提交");
                        return;
                    }
                    throw new SubmissionAbortedException("停机中断，任务回退待重跑");
                }
                UpstreamCallException effective = e;
                if (e.getKind() == UpstreamErrorKind.TASK_NOT_FOUND) {
                    // 提交阶段的 404 = 提交端点/路径配置错（此时任务还没创建，谈不上"丢失"）：
                    // 按渠道病处理——喂熔断、换下一候选；候选耗尽时错误码自然落 UPSTREAM_ALL_FAILED
                    effective = new UpstreamCallException(UpstreamErrorKind.UPSTREAM_SERVER_ERROR,
                            "提交接口返回 404（提交路径可能配置有误）: " + e.getMessage(), e.getHttpStatus());
                }
                recordAttempt(task, channel, candidate.member().getUpstreamModel(),
                        effective.getKind().name(), effective.getHttpStatus(), effective.getMessage(), costMs(t0), submitUrl);
                applyChannelAction(channel, effective.getKind());
                lastError = effective;
                lastErrorChannel = channel;
                if (!effective.getKind().isRetryable()) {
                    finisher.fail(taskId, effective.getKind().getExternalCode(), sanitize(effective.getMessage(), channel));
                    return;
                }
                log.warn("渠道提交失败，换下一候选: taskId={}, channel={}, kind={}, msg={}",
                        task.getTaskId(), channel.getId(), effective.getKind(), effective.getMessage());
            } catch (RuntimeException e) {
                if (upstreamHit) {
                    // 上游已受理，收尾写库重试后仍失败：绝不换渠道重发；任务保持 RUNNING。
                    // 上游凭证（任务号/结果地址）随日志落盘——库里的 upstream_task_id 没写进去，这里是唯一线索。
                    log.error("上游已受理但收尾写库失败（不重发，防重复扣费）: taskId={}, channel={}, 上游侧凭证={}",
                            task.getTaskId(), channel.getId(), describeOutcome(currentOutcome), e);
                    return;
                }
                if (e instanceof org.springframework.dao.DataAccessException) {
                    // 基础设施故障（DB/连接池）不是渠道的病：不记尝试、不喂熔断，抛回执行器回退待重跑
                    log.error("提交链路数据库异常，任务回退待重跑: taskId={}", task.getTaskId(), e);
                    throw e;
                }
                recordAttempt(task, channel, candidate.member().getUpstreamModel(),
                        UpstreamErrorKind.UNKNOWN.name(), null, e.getMessage(), costMs(t0), submitUrl);
                health.recordFailure(channel.getId());
                lastError = new UpstreamCallException(UpstreamErrorKind.UNKNOWN,
                        String.valueOf(e.getMessage()), null);
                lastErrorChannel = channel;
                log.error("提交链路内部异常: taskId={}, channel={}", task.getTaskId(), channel.getId(), e);
            } finally {
                guard.release(channel.getId());
            }
        }

        String code = lastError == null
                ? "NO_AVAILABLE_CHANNEL"
                : (tried > 0 ? "UPSTREAM_ALL_FAILED" : "NO_AVAILABLE_CHANNEL");
        // 脱敏带上最后一个失败渠道的域名（不传渠道 = 上游域名原样透给业务方，变相暴露路由）
        String msg = lastError == null ? "无可用上游渠道" : sanitize(lastError.getMessage(), lastErrorChannel);
        finisher.fail(taskId, code, msg);
    }

    /**
     * 受理后的收尾写库（存档 + 终态/任务号）。瞬时 DB 故障内部重试 3 次；
     * 全失败向上抛——调用方处于 upstreamHit 状态，绝不换渠道重发，只落日志（含上游凭证）。
     */
    private void persistOutcome(GenerateTask task, Long taskId, ImageProvider.SubmitOutcome outcome,
                                String requestJson, Channel channel) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                archiveUpstreamRequest(taskId, requestJson, channel.getId());
                if (outcome instanceof ImageProvider.SubmitOutcome.Completed completed) {
                    finisher.complete(taskId, completed.resultUrls(), completed.inlineB64());
                } else if (outcome instanceof ImageProvider.SubmitOutcome.Accepted accepted) {
                    taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                            .eq(GenerateTask::getId, taskId)
                            .set(GenerateTask::getUpstreamTaskId, accepted.upstreamTaskId())
                            .set(GenerateTask::getPollCount, 0)
                            .set(GenerateTask::getRerunCount, 0)
                            .set(GenerateTask::getNotFoundCount, 0)
                            .set(GenerateTask::getNextPollTime, LocalDateTime.now()
                                    .plusNanos(config.getLong(ConfigKeys.POLL_INITIAL_INTERVAL_MS) * 1_000_000))
                            .set(GenerateTask::getUpdatedAt, LocalDateTime.now()));
                }
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("收尾写库失败（第 {}/3 次）: taskId={}", attempt, taskId, e);
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    /** 上游受理凭证（排障用）：异步=任务号；同步=结果地址（b64 内联不落日志，只报张数）。 */
    private String describeOutcome(ImageProvider.SubmitOutcome outcome) {
        if (outcome instanceof ImageProvider.SubmitOutcome.Accepted accepted) {
            return "upstreamTaskId=" + accepted.upstreamTaskId();
        }
        if (outcome instanceof ImageProvider.SubmitOutcome.Completed completed) {
            return completed.inlineB64()
                    ? "inlineB64=" + completed.resultUrls().size() + "张(b64内联)"
                    : "urls=" + completed.resultUrls();
        }
        return "unknown";
    }

    private void applyChannelAction(Channel channel, UpstreamErrorKind kind) {        switch (kind.getChannelAction()) {
            case COOLDOWN -> cooldown.cool(channel.getId());
            case AUTO_DISABLE -> channelOps.autoDisable(channel.getId(),
                    kind == UpstreamErrorKind.BALANCE_EXHAUSTED ? "上游余额耗尽，自动停用" : "上游 Key 失效/被拒，自动停用");
            case BREAKER_FEED -> health.recordFailure(channel.getId());
            case NONE -> {
            }
        }
    }

    private void archiveUpstreamRequestBody(Long taskId, String submitUrl, String requestJson) {
        taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getId, taskId)
                .set(GenerateTask::getUpstreamRequest, requestJson)
                .set(GenerateTask::getUpstreamUrl, submitUrl)
                .set(GenerateTask::getUpdatedAt, LocalDateTime.now()));
    }

    private void archiveUpstreamRequest(Long taskId, String requestJson, Long channelId) {
        taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getId, taskId)
                .set(GenerateTask::getUpstreamRequest, requestJson)
                .set(GenerateTask::getChannelId, channelId)
                .set(GenerateTask::getUpdatedAt, LocalDateTime.now()));
    }

    private synchronized void recordAttempt(GenerateTask task, Channel channel, String upstreamModel,
                                            String errorKind, Integer httpStatus, String errorMsg,
                                            int costMs, String submitUrl) {
        int no = (task.getAttemptCount() == null ? 0 : task.getAttemptCount()) + 1;
        TaskAttempt attempt = new TaskAttempt();
        attempt.setTaskId(task.getId());
        attempt.setAttemptNo(no);
        attempt.setChannelId(channel.getId());
        attempt.setUpstreamModel(upstreamModel);
        attempt.setUpstreamUrl(submitUrl);
        attempt.setPhase("SUBMIT");
        attempt.setOutcome(errorKind == null ? "SUCCESS" : "FAILED");
        attempt.setErrorKind(errorKind);
        attempt.setHttpStatus(httpStatus);
        attempt.setErrorMsg(errorMsg == null ? null
                : errorMsg.substring(0, Math.min(errorMsg.length(), 500)));
        attempt.setCostMs(costMs);
        attempt.setCreatedAt(LocalDateTime.now());
        attemptMapper.insert(attempt);
        taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getId, task.getId())
                .set(GenerateTask::getAttemptCount, no)
                .set(GenerateTask::getUpdatedAt, LocalDateTime.now()));
        task.setAttemptCount(no);
    }

    /** 失败文案脱敏：抹掉上游域名，防止变相暴露路由。 */
    private String sanitize(String message, Channel channel) {
        String msg = message == null ? "上游调用失败" : message;
        if (channel != null && channel.getBaseUrl() != null) {
            try {
                String host = URI.create(channel.getBaseUrl()).getHost();
                if (host != null) {
                    msg = msg.replace(host, "upstream");
                }
            } catch (Exception ignored) {
                // 非法 URL 时保留原文
            }
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    private int costMs(long t0) {
        return (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - t0);
    }
}
