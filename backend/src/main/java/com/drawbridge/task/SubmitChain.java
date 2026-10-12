package com.drawbridge.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.adapter.ImageProvider;
import com.drawbridge.adapter.ProviderRegistry;
import com.drawbridge.adapter.UpstreamCallException;
import com.drawbridge.adapter.UpstreamErrorKind;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.entity.GroupMember;
import com.drawbridge.domain.entity.ModelGroup;
import com.drawbridge.domain.entity.TaskAttempt;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.domain.mapper.TaskAttemptMapper;
import com.drawbridge.health.ChannelHealthTracker;
import com.drawbridge.health.ChannelOpsService;
import com.drawbridge.health.CooldownRegistry;
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
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 提交链路（虚拟线程上执行）：派发器已领取任务并绑定渠道，这里对绑定渠道发起上游尝试。
 * 规则：
 * - 只有"渠道的病"（超时/5xx/429/没米/Key失效）才换下一候选；提示词/内容/参数类错误直接终态
 * - 换候选必须在全局锁内核查目标账号槽位（SlotClaims.rebindRunning，槽位随行转移）；
 *   未试候选全满 → 退回 SUBMITTED 保留进度（attempt/task_attempt 本就持久化），退避后派发器再来
 * - 尝试额度跨排队重入累计（attempt_count 对照 route.max_channel_attempts），耗尽判 UPSTREAM_ALL_FAILED
 * - 没米（精确余额信号）→ 账号级批量停用；Key 失效/宽匹配余额 → 单渠道停用 + 告警
 * - 429 → 渠道短冷却；网络/5xx/未知 → 熔断计数
 * - 上游受理成功后绝不重发（重发可能重复扣费）；轮询阶段的任务永远不进本链路
 * - 停机强制中断不是渠道病：已发过请求 → SYSTEM_SHUTDOWN 终态；没发过 → 回退重跑
 * - 2xx 但响应不可解析（BAD_RESPONSE）→ 终态不转移（上游可能已出图，转移即重复扣费）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmitChain {

    /** 换候选全满退队后的重派间隔（毫秒）：给饱和账号留出呼吸空隙，避免每秒空转 */
    private static final long REQUEUE_BACKOFF_MS = 3_000L;

    private final GenerateTaskMapper taskMapper;
    private final TaskAttemptMapper attemptMapper;
    private final RouteEngine routeEngine;
    private final ProviderRegistry registry;
    private final ChannelConfigService configService;
    private final SystemConfigService config;
    private final ChannelHealthTracker health;
    private final CooldownRegistry cooldown;
    private final ChannelOpsService channelOps;
    private final TaskFinisher finisher;
    private final SlotClaims slotClaims;

    /** 尝试流水写入互斥（同任务可能被回退重跑并发推进；ReentrantLock 防虚拟线程钉住） */
    private final ReentrantLock attemptLock = new ReentrantLock();

    public void run(Long taskId) {
        GenerateTask task = taskMapper.selectById(taskId);
        if (task == null || !TaskStatus.RUNNING.name().equals(task.getStatus())) {
            return;
        }
        SubmitPayload payload = SubmitPayload.parse(task.getBizRequest());
        int n = task.getImageCount() == null ? 1 : task.getImageCount();
        int submitTimeout = (int) config.getLong(ConfigKeys.SUBMIT_TIMEOUT_MS);
        int maxAttempts = config.getInt(ConfigKeys.ROUTE_MAX_CHANNEL_ATTEMPTS);

        Channel current = task.getChannelId() == null ? null : configService.channel(task.getChannelId());
        GroupMember member = current == null ? null : findEnabledMember(task.getGroupCode(), current.getId());
        if (current == null || member == null) {
            // 领取绑定的渠道在执行前被删/被移出分组：结构性失败，不退队（等也不会回来）
            finisher.fail(taskId, "NO_AVAILABLE_CHANNEL", "领取的渠道已不存在或未挂到该模型分组");
            return;
        }

        // 本次运行是否已向上游发出过请求：停机中断时决定"诚实终态"还是"安全回退"的判据
        boolean requestSent = false;
        UpstreamCallException lastError = null;
        Channel lastErrorChannel = null;

        while (true) {
            int attempted = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
            if (attempted >= maxAttempts) {
                break; // 尝试额度跨重入累计耗尽 → 统一收尾
            }
            if (Thread.currentThread().isInterrupted()) {
                // 停机强制中断：不再向任何渠道发请求（还没碰过上游 → 可安全回退）
                throw new SubmissionAbortedException("停机中断，本次提交在发请求前终止");
            }
            long t0 = System.currentTimeMillis();
            String submitUrl = null;
            boolean upstreamHit = false;
            ImageProvider.SubmitOutcome currentOutcome = null;
            try {
                ImageProvider provider = registry.find(current.getProtocol()).orElse(null);
                if (provider == null) {
                    recordAttempt(task, current, member.getUpstreamModel(),
                            "NO_PROVIDER", null, "该协议族无适配器", costMs(t0), null);
                    lastError = new UpstreamCallException(UpstreamErrorKind.UNKNOWN,
                            "渠道协议无适配器: " + current.getProtocol(), null);
                    lastErrorChannel = current;
                } else {
                    ChannelConfigService.ChannelRuntime rt = configService.runtimeOf(current);
                    ImageProvider.SubmitCommand cmd = new ImageProvider.SubmitCommand(
                            member.getUpstreamModel(), payload.prompt(), n,
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
                        recordAttempt(task, current, member.getUpstreamModel(),
                                null, null, null, costMs(t0), submitUrl);
                    } catch (Exception ae) {
                        log.warn("尝试流水写入失败（不影响受理，任务时间线会缺一条）: taskId={}", task.getTaskId(), ae);
                    }
                    health.recordSuccess(current.getId());
                    // 收尾写库（存档+终态/任务号）带内部重试；全失败向上抛，由 upstreamHit 分支兜住（带上游凭证落日志）
                    persistOutcome(task, taskId, outcome, requestJson, current);
                    log.info("上游受理成功: taskId={}, channel={}, outcome={}",
                            task.getTaskId(), current.getId(), describeOutcome(outcome));
                    return;
                }
            } catch (UpstreamCallException e) {
                if (e.getKind() == UpstreamErrorKind.SHUTDOWN_INTERRUPT) {
                    // 停机中断不是渠道病：不喂熔断不停用。已发过请求 → 诚实终态（上游可能已受理，
                    // 回退重跑会重复扣费）；没发过 → 回退 SUBMITTED 交重启后重跑。
                    recordAttempt(task, current, member.getUpstreamModel(),
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
                recordAttempt(task, current, member.getUpstreamModel(),
                        effective.getKind().name(), effective.getHttpStatus(), effective.getMessage(), costMs(t0), submitUrl);
                applyChannelAction(current, effective);
                lastError = effective;
                lastErrorChannel = current;
                if (!effective.getKind().isRetryable()) {
                    finisher.fail(taskId, effective.getKind().getExternalCode(), sanitize(effective.getMessage(), current));
                    return;
                }
                log.warn("渠道提交失败，换下一候选: taskId={}, channel={}, kind={}, msg={}",
                        task.getTaskId(), current.getId(), effective.getKind(), effective.getMessage());
            } catch (RuntimeException e) {
                if (upstreamHit) {
                    // 上游已受理，收尾写库重试后仍失败：绝不换渠道重发；任务保持 RUNNING。
                    // 上游凭证（任务号/结果地址）随日志落盘——库里的 upstream_task_id 没写进去，这里是唯一线索。
                    log.error("上游已受理但收尾写库失败（不重发，防重复扣费）: taskId={}, channel={}, 上游侧凭证={}",
                            task.getTaskId(), current.getId(), describeOutcome(currentOutcome), e);
                    return;
                }
                if (e instanceof org.springframework.dao.DataAccessException) {
                    // 基础设施故障（DB/连接池）不是渠道的病：不记尝试、不喂熔断，抛回执行器回退待重跑
                    log.error("提交链路数据库异常，任务回退待重跑: taskId={}", task.getTaskId(), e);
                    throw e;
                }
                recordAttempt(task, current, member.getUpstreamModel(),
                        UpstreamErrorKind.UNKNOWN.name(), null, e.getMessage(), costMs(t0), submitUrl);
                health.recordFailure(current.getId());
                lastError = new UpstreamCallException(UpstreamErrorKind.UNKNOWN,
                        String.valueOf(e.getMessage()), null);
                lastErrorChannel = current;
                log.error("提交链路内部异常: taskId={}, channel={}", task.getTaskId(), current.getId(), e);
            }

            // 当前渠道失败且可重试：找下一个未试过且当前有空槽的候选
            NextHop next = nextHop(task, lastError);
            if (next == null) {
                break; // 无未试候选（或结构死）→ 统一收尾失败
            }
            if (next.requeued()) {
                return; // 未试候选全满：已退回队列保留进度，派发器稍后再来
            }
            current = next.candidate().channel();
            member = next.candidate().member();
            task = taskMapper.selectById(taskId);
            if (task == null || !TaskStatus.RUNNING.name().equals(task.getStatus())) {
                return; // 换绑后被并发推进（清扫/停机），收手
            }
        }

        String code = lastError == null ? "NO_AVAILABLE_CHANNEL" : "UPSTREAM_ALL_FAILED";
        // 脱敏带上最后一个失败渠道的域名（不传渠道 = 上游域名原样透给业务方，变相暴露路由）
        String msg = lastError == null ? "无可用上游渠道" : sanitize(lastError.getMessage(), lastErrorChannel);
        finisher.fail(taskId, code, msg);
    }

    private record NextHop(RouteEngine.Candidate candidate, boolean requeued) {
    }

    /**
     * 找下一个未试过且当前有空槽的候选（锁内换绑）。已试渠道从 task_attempt 流水查——
     * 进度持久化在库，跨排队重入不丢。
     *
     * @return null = 无未试候选（结构性收尾失败）；requeued=true = 有未试候选但全满，已退回队列
     */
    private NextHop nextHop(GenerateTask task, UpstreamCallException lastError) {
        RouteEngine.GroupRouting st = routeEngine.candidateState(task.getGroupCode());
        if (st.state() == RouteEngine.Availability.STRUCTURAL_DOWN) {
            return null;
        }
        Set<Long> tried = attemptMapper.selectList(Wrappers.<TaskAttempt>lambdaQuery()
                        .select(TaskAttempt::getChannelId)
                        .eq(TaskAttempt::getTaskId, task.getId()))
                .stream().map(TaskAttempt::getChannelId).collect(Collectors.toSet());
        boolean sawUntried = false;
        for (RouteEngine.Candidate c : st.candidates()) {
            if (tried.contains(c.channel().getId())) {
                continue;
            }
            sawUntried = true;
            switch (slotClaims.rebindRunning(task.getId(), c.channel())) {
                case CLAIMED -> {
                    return new NextHop(c, false);
                }
                case STALE -> {
                    return null; // 状态被并发推进（终态/回退），收手
                }
                case NO_SLOT -> {
                    // 该候选账号已满，看下一个（跳过饱和的——错误切换不受"满也不跳"约束）
                }
            }
        }
        if (!sawUntried) {
            return null; // 候选全部试过 → 收尾失败（UPSTREAM_ALL_FAILED），不是排队
        }
        // 有未试候选但全满：退回队列保留进度，等饱和账号出空位（排队语义，不失败）
        requeueRunning(task);
        return new NextHop(null, true);
    }

    /** 退回队列：恢复排队死线（SUBMITTED=排队死线双语义），清渠道绑定（槽位出账），小退避后重派。 */
    private void requeueRunning(GenerateTask task) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime queueDeadline = QueueDeadlines.queueDeadline(task.getCreatedAt(),
                    config.getLong(ConfigKeys.TASK_QUEUE_MAX_WAIT_MS));
            if (now.isAfter(queueDeadline)) {
                finisher.fail(task.getId(), "QUEUE_WAIT_TIMEOUT", "排队等待超时（候选渠道持续饱和，超过排队上限）");
                return;
            }
            taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                    .eq(GenerateTask::getId, task.getId())
                    .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                    .isNull(GenerateTask::getUpstreamTaskId)
                    .set(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                    .set(GenerateTask::getChannelId, null)
                    .set(GenerateTask::getNextPollTime, now.plusNanos(REQUEUE_BACKOFF_MS * 1_000_000))
                    .set(GenerateTask::getExpiresAt, queueDeadline)
                    .set(GenerateTask::getUpdatedAt, now));
            log.info("候选渠道全满，任务退回队列保留进度: taskId={}, 已试渠道数={}",
                    task.getTaskId(), task.getAttemptCount());
        } catch (Exception e) {
            log.error("退队失败（保持 RUNNING 由兜底清扫处理）: taskId={}", task.getId(), e);
        }
    }

    private GroupMember findEnabledMember(String groupCode, Long channelId) {
        ModelGroup group = configService.groupByCode(groupCode);
        if (group == null) {
            return null;
        }
        return configService.current().membersByGroupId()
                .getOrDefault(group.getId(), List.of()).stream()
                .filter(m -> m.getEnabled() != null && m.getEnabled() == 1)
                .filter(m -> channelId.equals(m.getChannelId()))
                .findFirst().orElse(null);
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

    private void applyChannelAction(Channel channel, UpstreamCallException e) {
        UpstreamErrorKind kind = e.getKind();
        switch (kind.getChannelAction()) {
            case COOLDOWN -> cooldown.cool(channel.getId());
            case AUTO_DISABLE -> {
                String reason = kind == UpstreamErrorKind.BALANCE_EXHAUSTED
                        ? "上游余额耗尽，自动停用" : "上游 Key 失效/被拒，自动停用";
                if (kind == UpstreamErrorKind.BALANCE_EXHAUSTED && e.isAccountScope()) {
                    // 精确余额信号：同账号（同 base_url+apiKey）渠道一起停，充值后整批恢复
                    channelOps.autoDisableAccount(channel.getAccountKey(), reason);
                } else {
                    channelOps.autoDisable(channel.getId(), reason);
                }
            }
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

    private void recordAttempt(GenerateTask task, Channel channel, String upstreamModel,
                               String errorKind, Integer httpStatus, String errorMsg,
                               int costMs, String submitUrl) {
        attemptLock.lock();
        try {
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
        } finally {
            attemptLock.unlock();
        }
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
