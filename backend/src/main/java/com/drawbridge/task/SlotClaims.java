package com.drawbridge.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.health.AccountRegistry;
import com.drawbridge.health.ConcurrencyGuard;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 槽位领取/换绑（全局锁内执行）：
 *   claimSubmitted  SUBMITTED→RUNNING 并绑定渠道（派发器初始领取）；
 *   rebindRunning   RUNNING 保持、channel_id 改绑（提交链路换候选，槽位随行转移）。
 * 锁内必须现查账号 RUNNING 数与全网总数（快照只用于选路，不作领取依据）；
 * UPDATE 在放锁前提交（本类无外层事务）。
 */
@Component
@RequiredArgsConstructor
public class SlotClaims {

    public enum ClaimResult {CLAIMED, STALE, NO_SLOT}

    private final GenerateTaskMapper taskMapper;
    private final ConcurrencyGuard guard;
    private final AccountRegistry accountRegistry;
    private final SystemConfigService config;
    private final DispatchLock dispatchLock;

    public ClaimResult claimSubmitted(Long taskId, Channel channel) {
        return dispatchLock.supply(() -> {
            if (!hasSlot(channel)) {
                return ClaimResult.NO_SLOT;
            }
            LocalDateTime now = LocalDateTime.now();
            int rows = taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                    .eq(GenerateTask::getId, taskId)
                    .eq(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                    .set(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                    .set(GenerateTask::getChannelId, channel.getId())
                    .set(GenerateTask::getExpiresAt, QueueDeadlines.executionDeadline(now,
                            config.getLong(ConfigKeys.TASK_MAX_LIFETIME_MS)))
                    .set(GenerateTask::getNextPollTime,
                            now.plusNanos(config.getLong(ConfigKeys.SUBMIT_LEASE_MS) * 1_000_000))
                    .set(GenerateTask::getUpdatedAt, now));
            return rows == 1 ? ClaimResult.CLAIMED : ClaimResult.STALE;
        });
    }

    public ClaimResult rebindRunning(Long taskId, Channel channel) {
        return dispatchLock.supply(() -> {
            if (!hasSlot(channel)) {
                return ClaimResult.NO_SLOT;
            }
            int rows = taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                    .eq(GenerateTask::getId, taskId)
                    .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                    .isNull(GenerateTask::getUpstreamTaskId)
                    .set(GenerateTask::getChannelId, channel.getId())
                    .set(GenerateTask::getUpdatedAt, LocalDateTime.now()));
            return rows == 1 ? ClaimResult.CLAIMED : ClaimResult.STALE;
        });
    }

    private boolean hasSlot(Channel channel) {
        String ak = channel.getAccountKey();
        if (ak == null || ak.isBlank()) {
            return false;
        }
        int capacity = accountRegistry.capacityOf(ak);
        if (capacity <= 0) {
            return false; // 容量 0 = 暂停派发
        }
        if (guard.runningOfAccount(ak) >= capacity) {
            return false;
        }
        return guard.runningTotal() < config.getInt(ConfigKeys.ROUTE_GLOBAL_CONCURRENCY);
    }
}
