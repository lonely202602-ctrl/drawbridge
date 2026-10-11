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
 * 回退守则：只有确定持有任务（领取成功后链路抛异常）才回退——领取 UPDATE 自身失败
 * （如连接池打满超时）时归属不明，绝不回退，否则会把别的线程正在调上游的任务打回重跑
 * （重复提交上游）。领取已落库但返回断连的极端情况会留下无主 RUNNING 任务，
 * 由兜底清扫②在 stale_minutes 后回收（其时 upstream_request 为空，重跑安全）。
 * 回退退避：5s→15s→45s→135s→240s（计数用独立列 rerun_count，受理成功即清零），
 * 自动重跑超 5 次判 INTERNAL_ERROR 终态——确定性错误不该每秒热循环刷满任务生命周期；
 * 已受理上游（有任务号）的任务即使超限也不判失败，保持 RUNNING 交轮询/清扫。
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
            int rows;
            try {
                rows = tryClaim(taskId);
            } catch (Exception e) {
                // 领取失败=归属不明：可能别的线程正领着它在调上游，回退会把它的任务打回重跑
                log.warn("任务领取失败，不回退（等待下轮扫描/兜底清扫）: taskId={}", taskId, e);
                return;
            }
            if (rows != 1) {
                return;
            }
            try {
                chain.run(taskId);
            } catch (Exception e) {
                log.error("提交链路异常，回退待重跑: taskId={}", taskId, e);
                rollbackClaim(taskId);
            }
        });
    }

    /** 领取：原子把 SUBMITTED 翻成 RUNNING 并推进租约；返回影响行数（1=本线程确定持有）。 */
    private int tryClaim(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getId, taskId)
                .eq(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                .set(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                .set(GenerateTask::getNextPollTime,
                        now.plusNanos(config.getLong(ConfigKeys.SUBMIT_LEASE_MS) * 1_000_000))
                .set(GenerateTask::getUpdatedAt, now));
    }

    /**
     * 灾难回退：仅当任务尚未受理上游（无上游任务号）时退回 SUBMITTED。
     * 回退带退避（独立列 rerun_count 作重跑计数，受理成功清零），超限判内部错误终态——
     * 确定性错误（缺列/约束冲突）不该每秒热循环重跑；已受理的任务超限也不判失败。
     */
    private void rollbackClaim(Long taskId) {
        try {
            GenerateTask t = taskMapper.selectById(taskId);
            if (t == null) {
                return;
            }
            int reruns = t.getRerunCount() == null ? 0 : t.getRerunCount();
            if (reruns >= MAX_AUTO_RERUNS) {
                if (t.getUpstreamTaskId() != null) {
                    // 上游已受理（可能正在出图）：判失败=明明花了钱说没花，保持 RUNNING 交轮询/清扫
                    log.warn("回退重跑超限但上游已受理，保持 RUNNING: taskId={}, upstreamTaskId={}",
                            taskId, t.getUpstreamTaskId());
                    return;
                }
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
                    .set(GenerateTask::getRerunCount, reruns + 1)
                    .set(GenerateTask::getNextPollTime, LocalDateTime.now().plusNanos(backoffMs * 1_000_000))
                    .set(GenerateTask::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception inner) {
            log.error("回退失败，等待兜底清扫: taskId={}", taskId, inner);
        }
    }
}
