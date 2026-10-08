package com.drawbridge.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.billing.BillingService;
import com.drawbridge.common.JsonUtil;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务终态推进（提交链路/轮询链路/兜底清扫共用）：
 * 终态一次性写入（带状态前置条件，防并发双写）→ 结算（成功确认冻结额/失败退款）→ 释放提交等待者。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskFinisher {

    private final GenerateTaskMapper taskMapper;
    private final BillingService billing;
    private final SystemConfigService config;
    private final MilestoneRegistry milestones;

    @org.springframework.transaction.annotation.Transactional
    public void complete(Long taskId, List<String> urls, boolean inlineB64, BigDecimal cost) {
        GenerateTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        BigDecimal frozen = task.getFreezeAmount() == null ? BigDecimal.ZERO : task.getFreezeAmount();
        LocalDateTime now = LocalDateTime.now();
        var result = JsonUtil.MAPPER.createObjectNode();
        var urlArr = result.putArray("urls");
        urls.forEach(urlArr::add);
        if (inlineB64) {
            result.put("inline", "b64_json");
            result.put("note", "结果为 base64 内嵌数据，已随提交响应返回，网关不落盘");
        }
        String resultJson = JsonUtil.truncateBytes(result.toString(),
                config.getInt(ConfigKeys.RETENTION_BODY_MAX_BYTES));
        int rows = taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getId, taskId)
                .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                .set(GenerateTask::getStatus, TaskStatus.SUCCEEDED.name())
                .set(GenerateTask::getResult, resultJson)
                .set(GenerateTask::getFinishTime, now)
                .set(GenerateTask::getSettleAmount, frozen)
                .set(GenerateTask::getUpdatedAt, now));
        if (rows == 0) {
            log.warn("任务完成写入 0 行（终态已被并发推进，跳过结算）: taskId={}", taskId);
            return;
        }
        billing.settleSuccess(taskMapper.selectById(taskId), cost);
        releaseAfterCommit(task.getTaskId());
        log.info("任务成功: taskId={}, urls={}, inlineB64={}", task.getTaskId(), urls.size(), inlineB64);
    }

    @org.springframework.transaction.annotation.Transactional
    public void fail(Long taskId, String errorCode, String errorMsg) {
        LocalDateTime now = LocalDateTime.now();
        int rows = taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getId, taskId)
                .in(GenerateTask::getStatus, TaskStatus.SUBMITTED.name(), TaskStatus.RUNNING.name())
                .set(GenerateTask::getStatus, TaskStatus.FAILED.name())
                .set(GenerateTask::getErrorCode, errorCode)
                .set(GenerateTask::getErrorMsg, errorMsg == null ? null
                        : errorMsg.substring(0, Math.min(errorMsg.length(), 500)))
                .set(GenerateTask::getFinishTime, now)
                .set(GenerateTask::getSettleAmount, BigDecimal.ZERO)
                .set(GenerateTask::getUpdatedAt, now));
        if (rows == 0) {
            log.warn("任务失败写入 0 行（终态已被并发推进，跳过退款）: taskId={}", taskId);
            return;
        }
        GenerateTask fresh = taskMapper.selectById(taskId);
        billing.settleFailure(fresh, errorCode);
        releaseAfterCommit(fresh.getTaskId());
        log.warn("任务失败: taskId={}, code={}, msg={}", fresh.getTaskId(), errorCode, errorMsg);
    }

    /** 事务提交后再唤醒提交等待者，保证等待者醒来一定能读到终态数据。 */
    private void releaseAfterCommit(String taskId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            milestones.release(taskId);
                        }
                    });
        } else {
            milestones.release(taskId);
        }
    }
}
