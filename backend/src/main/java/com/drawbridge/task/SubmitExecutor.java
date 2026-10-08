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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 提交执行器：SUBMITTED 任务的领取与派发（DB 当队列）。
 * 领取 = 带状态前置条件的原子 UPDATE（推进 next_poll_time 即租约），天然防重复领取、
 * 崩溃后由兜底清扫回收。扫描是安全网，业务方提交后还会直接 kick 提时延。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmitExecutor {

    private final GenerateTaskMapper taskMapper;
    private final SubmitChain chain;
    private final SystemConfigService config;

    private final ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor();

    @Scheduled(fixedDelay = 1000)
    public void scan() {
        try {
            scanDue();
        } catch (Exception e) {
            log.error("提交扫描异常（下一轮重试）", e);
        }
    }

    public void scanDue() {
        int batch = config.getInt(ConfigKeys.POLL_BATCH_SIZE);
        LocalDateTime now = LocalDateTime.now();
        List<GenerateTask> due = taskMapper.selectList(Wrappers.<GenerateTask>lambdaQuery()
                .select(GenerateTask::getId)
                .eq(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                .le(GenerateTask::getNextPollTime, now)
                .orderByAsc(GenerateTask::getNextPollTime)
                .last("limit " + batch));
        due.forEach(t -> kick(t.getId()));
    }

    /** 提交接口创建任务后直接调用（幂等：领取原子性保证只跑一次）。 */
    public void kick(Long taskId) {
        vexec.submit(() -> {
            try {
                LocalDateTime now = LocalDateTime.now();
                int rows = taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                        .eq(GenerateTask::getId, taskId)
                        .eq(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                        .set(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                        .set(GenerateTask::getNextPollTime,
                                now.plusNanos(config.getLong(ConfigKeys.SUBMIT_LEASE_MS) * 1_000_000))
                        .set(GenerateTask::getUpdatedAt, now));
                if (rows == 1) {
                    chain.run(taskId);
                }
            } catch (Exception e) {
                log.error("提交任务处理异常，回退待执行: taskId={}", taskId, e);
                rollbackClaim(taskId);
            }
        });
    }

    /** 灾难回退：仅当任务尚未受理上游（无上游任务号）时退回 SUBMITTED。 */
    private void rollbackClaim(Long taskId) {
        try {
            taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                    .eq(GenerateTask::getId, taskId)
                    .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                    .isNull(GenerateTask::getUpstreamTaskId)
                    .set(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                    .set(GenerateTask::getNextPollTime, LocalDateTime.now())
                    .set(GenerateTask::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception inner) {
            log.error("回退失败，等待兜底清扫: taskId={}", taskId, inner);
        }
    }
}
