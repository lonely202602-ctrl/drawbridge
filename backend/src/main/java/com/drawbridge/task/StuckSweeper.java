package com.drawbridge.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 兜底清扫（追踪保证的最后一道网，每分钟）：
 * ① 超过生命周期的非终态任务 → FAILED(TIMEOUT)
 * ② RUNNING 无上游任务号且停滞 → 退回 SUBMITTED（提交链路崩溃残留）
 * ③ RUNNING 有上游任务号且停滞 → 重置轮询时间（轮询链路崩溃残留）
 * ④ SUBMITTED 但 next_poll_time 在未来且停滞 → 立即可扫
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckSweeper {

    private final GenerateTaskMapper taskMapper;
    private final TaskFinisher finisher;
    private final SystemConfigService config;

    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        try {
            doSweep();
        } catch (Exception e) {
            log.error("兜底清扫异常（下一轮重试）", e);
        }
    }

    void doSweep() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusMinutes(config.getInt(ConfigKeys.TASK_STALE_MINUTES));

        // ① 超时终态（先于其它处理，防僵死任务被重扫）
        List<GenerateTask> expired = taskMapper.selectList(Wrappers.<GenerateTask>lambdaQuery()
                .select(GenerateTask::getId)
                .in(GenerateTask::getStatus, TaskStatus.SUBMITTED.name(), TaskStatus.RUNNING.name())
                .lt(GenerateTask::getExpiresAt, now)
                .last("limit 200"));
        expired.forEach(t -> finisher.fail(t.getId(), "TIMEOUT", "任务超过最大生命周期"));

        // ② 提交链路崩溃残留（未受理上游，可安全重跑）
        taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                .isNull(GenerateTask::getUpstreamTaskId)
                .lt(GenerateTask::getUpdatedAt, staleBefore)
                .set(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                .set(GenerateTask::getNextPollTime, now)
                .set(GenerateTask::getUpdatedAt, now));

        // ③ 轮询链路崩溃残留（租约丢失，重置轮询时间）
        taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                .isNotNull(GenerateTask::getUpstreamTaskId)
                .lt(GenerateTask::getUpdatedAt, staleBefore)
                .set(GenerateTask::getNextPollTime, now)
                .set(GenerateTask::getUpdatedAt, now));

        // ④ SUBMITTED 卡在未来时间点
        taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                .gt(GenerateTask::getNextPollTime, now)
                .lt(GenerateTask::getUpdatedAt, staleBefore)
                .set(GenerateTask::getNextPollTime, now)
                .set(GenerateTask::getUpdatedAt, now));
    }
}
