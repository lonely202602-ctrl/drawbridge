package com.drawbridge.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.adapter.ImageProvider;
import com.drawbridge.adapter.ProviderRegistry;
import com.drawbridge.adapter.UpstreamCallException;
import com.drawbridge.adapter.UpstreamErrorKind;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.health.ChannelOpsService;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 异步任务轮询器：扫到期（next_poll_time 到点）的 RUNNING 任务，
 * 虚拟线程逐个查上游 → 状态机推进。间隔指数退避（初始/上限可配），
 * 领取即租约（崩溃自动重扫），轮询期网络抖动只重试不毁任务。
 * 熔断器只吃提交链路：轮询成败不进熔断统计（渠道病了由提交失败触发熔断/停用 + 任务生命周期超时兜底）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPoller {

    private final GenerateTaskMapper taskMapper;
    private final ProviderRegistry registry;
    private final ChannelConfigService configService;
    private final SystemConfigService config;
    private final ChannelOpsService channelOps;
    private final TaskFinisher finisher;

    private final ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor();

    /** 上游 404 宽限：受理后短暂 404 可能是最终一致性抖动，连续 N 次都是 404 才判任务丢失。 */
    private static final int TASK_NOT_FOUND_GRACE = 4;

    /** 优雅停机：等在途轮询最多 30 秒（防止发版掐死在途轮询，交由清扫③回收）。 */
    @PreDestroy
    public void shutdown() {
        vexec.shutdown();
        try {
            if (!vexec.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("优雅停机超时（30s），强制中断在途轮询");
                vexec.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            vexec.shutdownNow();
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void scan() {
        try {
            scanOnce();
        } catch (Exception e) {
            log.error("轮询扫描异常（下一轮重试）", e);
        }
    }

    public void scanOnce() {
        int batch = config.getInt(ConfigKeys.POLL_BATCH_SIZE);
        LocalDateTime now = LocalDateTime.now();
        List<GenerateTask> due = taskMapper.selectList(Wrappers.<GenerateTask>lambdaQuery()
                .select(GenerateTask::getId)
                .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                .isNotNull(GenerateTask::getUpstreamTaskId)
                .le(GenerateTask::getNextPollTime, now)
                .orderByAsc(GenerateTask::getNextPollTime)
                .last("limit " + batch));
        due.forEach(t -> vexec.submit(() -> processPoll(t.getId())));
    }

    private void processPoll(Long taskId) {
        GenerateTask task = null;
        try {
            LocalDateTime now = LocalDateTime.now();
            int rows = taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                    .eq(GenerateTask::getId, taskId)
                    .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                    .isNotNull(GenerateTask::getUpstreamTaskId)
                    .le(GenerateTask::getNextPollTime, now)
                    .set(GenerateTask::getNextPollTime,
                            now.plusNanos(config.getLong(ConfigKeys.POLL_LEASE_MS) * 1_000_000))
                    .set(GenerateTask::getLastPollAt, now)
                    .set(GenerateTask::getUpdatedAt, now));
            if (rows != 1) {
                return;
            }
            task = taskMapper.selectById(taskId);
            if (task.getExpiresAt() != null && task.getExpiresAt().isBefore(LocalDateTime.now())) {
                finisher.fail(taskId, "TIMEOUT", "任务超过最大生命周期");
                return;
            }
            Channel channel = configService.channel(task.getChannelId());
            ImageProvider provider = channel == null ? null : registry.find(channel.getProtocol()).orElse(null);
            if (channel == null || provider == null) {
                // 渠道被删除或协议被改：任务已无法追踪，直接终态（不再空转到超时）
                finisher.fail(taskId, "CHANNEL_MISCONFIGURED", "任务所属渠道已被删除或协议已变更，无法继续追踪");
                return;
            }
            ChannelConfigService.ChannelRuntime rt = configService.runtimeOf(channel);
            ImageProvider.PollOutcome outcome = provider.poll(task.getUpstreamTaskId(), rt);
            switch (outcome.phase()) {
                case SUCCEEDED -> finisher.complete(taskId, outcome.resultUrls(), outcome.inlineB64());
                case FAILED -> finisher.fail(taskId, "UPSTREAM_TASK_FAILED", sanitize(outcome.failMessage(), channel));
                case RUNNING -> reschedule(task, (task.getPollCount() == null ? 0 : task.getPollCount()) + 1);
            }
        } catch (UpstreamCallException e) {
            Channel channel = task == null ? null : configService.channel(task.getChannelId());
            if (channel != null && (e.getKind() == UpstreamErrorKind.BALANCE_EXHAUSTED
                    || e.getKind() == UpstreamErrorKind.AUTH_FAILED)) {
                // 轮询期才发现没米/Key 失效：任务已受理不能重试，停渠道 + 任务失败（精确分类，保留自动停用）
                String reason = e.getKind() == UpstreamErrorKind.BALANCE_EXHAUSTED
                        ? "上游余额耗尽，自动停用" : "上游 Key 失效/被拒，自动停用";
                if (e.getKind() == UpstreamErrorKind.BALANCE_EXHAUSTED && e.isAccountScope()) {
                    // 精确余额信号：同账号渠道批量停用（充值后整批恢复）
                    channelOps.autoDisableAccount(channel.getAccountKey(), reason);
                } else {
                    channelOps.autoDisable(channel.getId(), reason);
                }
                finisher.fail(taskId, "UPSTREAM_TASK_FAILED", sanitize(e.getMessage(), channel));
                return;
            }
            if (e.getKind() == UpstreamErrorKind.TASK_NOT_FOUND
                    || (e.getHttpStatus() != null && e.getHttpStatus() == 404)) {
                // 上游 404 可能是受理后的最终一致性抖动或 queryPath 配错，不能一次就杀已付费任务：
                // 连续 TASK_NOT_FOUND_GRACE 次都是 404 才判丢失终态；任何非 404 轮询都会清零计数
                int notFound = (task.getNotFoundCount() == null ? 0 : task.getNotFoundCount()) + 1;
                if (notFound >= TASK_NOT_FOUND_GRACE) {
                    finisher.fail(taskId, "UPSTREAM_TASK_LOST",
                            "上游任务不存在或已被清理（连续 " + notFound + " 次 404）: " + sanitize(e.getMessage(), channel));
                } else {
                    log.warn("上游返回 404（第 {}/{} 次），宽限重试: taskId={}",
                            notFound, TASK_NOT_FOUND_GRACE, taskId);
                    long interval = computeInterval(task.getPollCount() == null ? 0 : task.getPollCount());
                    taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                            .eq(GenerateTask::getId, taskId)
                            .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                            .set(GenerateTask::getNotFoundCount, notFound)
                            .set(GenerateTask::getNextPollTime, LocalDateTime.now().plusNanos(interval * 1_000_000))
                            .set(GenerateTask::getUpdatedAt, LocalDateTime.now()));
                }
                return;
            }
            if (e.getKind() == UpstreamErrorKind.BAD_RESPONSE) {
                // 轮询期收到垃圾响应按网络抖动退避重试（不喂熔断，任务生命周期兜底）
                log.warn("轮询响应无法解析，按当前间隔重试: taskId={}", taskId);
                reschedule(task, task.getPollCount() == null ? 0 : task.getPollCount());
                return;
            }
            if (!e.getKind().isRetryable()) {
                // 内容/参数类：上游已明确拒绝，重试不会变好
                finisher.fail(taskId, e.getKind().getExternalCode(), sanitize(e.getMessage(), channel));
                return;
            }
            // 网络/5xx/429：按当前间隔退避重试（不喂熔断，任务生命周期兜底）
            log.warn("轮询异常，按当前间隔重试: taskId={}, kind={}, msg={}",
                    taskId, e.getKind(), e.getMessage());
            if (task != null) {
                reschedule(task, task.getPollCount() == null ? 0 : task.getPollCount());
            }
        } catch (UnsupportedOperationException e) {
            // 渠道协议被改成不支持轮询的族（如异步改同步）：任务已无法追踪，直接终态
            finisher.fail(taskId, "CHANNEL_MISCONFIGURED", "任务所属渠道协议不支持轮询（协议可能已变更）");
        } catch (Exception e) {
            log.error("轮询处理异常: taskId={}", taskId, e);
            if (task != null) {
                reschedule(task, task.getPollCount() == null ? 0 : task.getPollCount());
            }
        }
    }

    /** 状态更新 + 指数退避：initial << pollCount，封顶 max。非 404 的正常轮询会清零连续 404 计数。 */
    private void reschedule(GenerateTask task, int pollCount) {
        long interval = computeInterval(pollCount);
        taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getId, task.getId())
                .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                .set(GenerateTask::getPollCount, pollCount)
                .set(GenerateTask::getNotFoundCount, 0)
                .set(GenerateTask::getNextPollTime, LocalDateTime.now().plusNanos(interval * 1_000_000))
                .set(GenerateTask::getUpdatedAt, LocalDateTime.now()));
    }

    private long computeInterval(int pollCount) {
        long initial = Math.max(500, config.getLong(ConfigKeys.POLL_INITIAL_INTERVAL_MS));
        long max = Math.max(initial, config.getLong(ConfigKeys.POLL_MAX_INTERVAL_MS));
        return Math.min(initial << Math.min(pollCount, 10), max);
    }

    private String sanitize(String message, Channel channel) {
        String msg = message == null ? "上游任务失败" : message;
        if (channel != null && channel.getBaseUrl() != null) {
            try {
                String host = URI.create(channel.getBaseUrl()).getHost();
                if (host != null) {
                    msg = msg.replace(host, "upstream");
                }
            } catch (Exception ignored) {
                // 保留原文
            }
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
