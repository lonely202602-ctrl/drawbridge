package com.drawbridge.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.adapter.ImageProvider;
import com.drawbridge.adapter.ProviderRegistry;
import com.drawbridge.adapter.UpstreamCallException;
import com.drawbridge.adapter.UpstreamErrorKind;
import com.drawbridge.billing.CostCalc;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.health.ChannelHealthTracker;
import com.drawbridge.health.ChannelOpsService;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 异步任务轮询器：扫到期（next_poll_time 到点）的 RUNNING 任务，
 * 虚拟线程逐个查上游 → 状态机推进。间隔指数退避（初始/上限可配），
 * 领取即租约（崩溃自动重扫），轮询期网络抖动只重试不毁任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPoller {

    private final GenerateTaskMapper taskMapper;
    private final ProviderRegistry registry;
    private final ChannelConfigService configService;
    private final SystemConfigService config;
    private final ChannelHealthTracker health;
    private final ChannelOpsService channelOps;
    private final TaskFinisher finisher;

    private final ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor();

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
                // 渠道配置缺失/协议未实现：按退避继续等（兜底清扫与超时兜底会处理极端情况）
                reschedule(task, task.getPollCount() == null ? 0 : task.getPollCount());
                return;
            }
            ChannelConfigService.ChannelRuntime rt = configService.runtimeOf(channel);
            ImageProvider.PollOutcome outcome = provider.poll(task.getUpstreamTaskId(), rt);
            int n = task.getImageCount() == null ? 1 : task.getImageCount();
            switch (outcome.phase()) {
                case SUCCEEDED -> {
                    health.recordSuccess(channel.getId());
                    finisher.complete(taskId, outcome.resultUrls(), outcome.inlineB64(),
                            CostCalc.costOf(channel, outcome.cost(), n));
                }
                case FAILED -> {
                    health.recordSuccess(channel.getId());
                    finisher.fail(taskId, "UPSTREAM_TASK_FAILED", sanitize(outcome.failMessage(), channel));
                }
                case RUNNING -> {
                    health.recordSuccess(channel.getId());
                    reschedule(task, (task.getPollCount() == null ? 0 : task.getPollCount()) + 1);
                }
            }
        } catch (UpstreamCallException e) {
            Channel channel = task == null ? null : configService.channel(task.getChannelId());
            if (channel != null && (e.getKind() == UpstreamErrorKind.BALANCE_EXHAUSTED
                    || e.getKind() == UpstreamErrorKind.AUTH_FAILED)) {
                // 轮询期才发现没米/Key 失效：任务已受理不能重试，停渠道 + 任务失败
                channelOps.autoDisable(channel.getId(),
                        e.getKind() == UpstreamErrorKind.BALANCE_EXHAUSTED ? "上游余额耗尽，自动停用" : "上游 Key 失效/被拒，自动停用");
                finisher.fail(taskId, "UPSTREAM_TASK_FAILED", sanitize(e.getMessage(), channel));
            } else {
                if (channel != null) {
                    health.recordFailure(channel.getId());
                }
                log.warn("轮询异常，按当前间隔重试: taskId={}, kind={}, msg={}",
                        taskId, e.getKind(), e.getMessage());
                if (task != null) {
                    reschedule(task, task.getPollCount() == null ? 0 : task.getPollCount());
                }
            }
        } catch (Exception e) {
            log.error("轮询处理异常: taskId={}", taskId, e);
            if (task != null) {
                reschedule(task, task.getPollCount() == null ? 0 : task.getPollCount());
            }
        }
    }

    /** 状态更新 + 指数退避：initial << pollCount，封顶 max。 */
    private void reschedule(GenerateTask task, int pollCount) {
        long interval = computeInterval(pollCount);
        taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getId, task.getId())
                .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                .set(GenerateTask::getPollCount, pollCount)
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
