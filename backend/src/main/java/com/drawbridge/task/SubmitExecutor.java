package com.drawbridge.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 提交执行器：SUBMITTED 任务的领取与派发（DB 当队列）。
 * 领取 = 带状态前置条件的原子 UPDATE（推进 next_poll_time 即租约），天然防重复领取、
 * 崩溃后由兜底清扫回收。扫描是安全网，业务方提交后还会直接 kick 提时延。
 *
 * 优雅停机：@PreDestroy 停止领取并等在途提交最多 30 秒，防止发版掐死在途任务。
 * 回退退避：DB 异常回退按 30s→60s→120s→240s 退避（计数复用 poll_count，受理成功即清零），
 * 同一任务自动重跑超 5 次判 INTERNAL_ERROR 终态——防止确定性错误每秒热循环刷满任务生命周期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmitExecutor {

    private static final int MAX_AUTO_RERUNS = 5;

    private final GenerateTaskMapper taskMapper;
    private final SubmitChain chain;
    private final TaskFinisher finisher;
    private final SystemConfigService config;

    private final ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    public void shutdown() {
        vexec.shutdown();
        try {
            if (!vexec.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("优雅停机超时（30s），强制中断在途提交");
                vexec.shutdownNow();
            } else {
                log.info("在途提交已全部收尾，提交执行器停止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            vexec.shutdownNow();
        }
    }

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

    /**
     * 灾难回退：仅当任务尚未受理上游（无上游任务号）时退回 SUBMITTED。
     * 回退带退避（复用 poll_count 作重跑计数，受理成功清零），超限判内部错误终态——
     * 确定性错误（缺列/约束冲突）不该每秒热循环重跑。
     */
    private void rollbackClaim(Long taskId) {
        try {
            GenerateTask t = taskMapper.selectById(taskId);
            if (t == null) {
                return;
            }
            int reruns = t.getPollCount() == null ? 0 : t.getPollCount();
            if (reruns >= MAX_AUTO_RERUNS) {
                finisher.fail(taskId, "INTERNAL_ERROR", "提交链路连续异常，自动重跑超限，请检查系统状态后重新提交");
                return;
            }
            long backoffMs = switch (reruns) {
                case 0 -> 5_000L;      // 首次回退：瞬时抖动（连接闪断/池打满）大多秒级恢复，尽快重试
                case 1 -> 15_000L;
                case 2 -> 45_000L;
                case 3 -> 135_000L;
                default -> 240_000L;   // 封顶 4 分钟，第 5 次仍失败判 INTERNAL_ERROR
            };
            taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                    .eq(GenerateTask::getId, taskId)
                    .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                    .isNull(GenerateTask::getUpstreamTaskId)
                    .set(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                    .set(GenerateTask::getPollCount, reruns + 1)
                    .set(GenerateTask::getNextPollTime, LocalDateTime.now().plusNanos(backoffMs * 1_000_000))
                    .set(GenerateTask::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception inner) {
            log.error("回退失败，等待兜底清扫: taskId={}", taskId, inner);
        }
    }
}
