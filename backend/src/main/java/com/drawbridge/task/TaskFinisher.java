package com.drawbridge.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.common.JsonUtil;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.entity.UsageLog;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.domain.mapper.UsageLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务终态推进（提交链路/轮询链路/兜底清扫共用）：
 * 终态 + 用量流水在同一个事务里原子写入（带状态前置条件，防并发双写）。
 * drawbridge 是纯网关：不计价、不扣费、不冻结，用量流水只做成功/失败统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskFinisher {

    private final GenerateTaskMapper taskMapper;
    private final UsageLogMapper usageLogMapper;

    @org.springframework.transaction.annotation.Transactional
    public void complete(Long taskId, List<String> urls, boolean inlineB64) {
        GenerateTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        var result = JsonUtil.MAPPER.createObjectNode();
        var urlArr = result.putArray("urls");
        urls.forEach(urlArr::add);
        if (inlineB64) {
            result.put("inline", "b64_json");
        }
        // 结果 JSON 不做截断：b64 内联图单张 1~2MB，截断会产生解析不了的残废 JSON。
        // 列为 MEDIUMTEXT(16MB)，仅在极端体积时整体降级为占位说明，保证任何情况下都是合法 JSON。
        String resultJson = result.toString();
        if (resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 15_000_000) {
            var fallback = JsonUtil.MAPPER.createObjectNode();
            fallback.putArray("urls");
            fallback.put("note", "结果体积过大未存档，请以提交响应中的 result 为准");
            resultJson = fallback.toString();
        }
        int rows = taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                .eq(GenerateTask::getId, taskId)
                .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                .set(GenerateTask::getStatus, TaskStatus.SUCCEEDED.name())
                .set(GenerateTask::getResult, resultJson)
                .set(GenerateTask::getFinishTime, now)
                .set(GenerateTask::getUpdatedAt, now));
        if (rows == 0) {
            log.warn("任务完成写入 0 行（终态已被并发推进）: taskId={}", taskId);
            return;
        }
        insertUsage(task, 1, null);
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
                .set(GenerateTask::getUpdatedAt, now));
        if (rows == 0) {
            log.warn("任务失败写入 0 行（终态已被并发推进）: taskId={}", taskId);
            return;
        }
        GenerateTask fresh = taskMapper.selectById(taskId);
        insertUsage(fresh, 0, errorCode);
        log.warn("任务失败: taskId={}, code={}, msg={}", fresh.getTaskId(), errorCode, errorMsg);
    }

    private void insertUsage(GenerateTask task, int success, String errorKind) {
        try {
            UsageLog usage = new UsageLog();
            usage.setTaskId(task.getTaskId());
            usage.setTokenId(task.getTokenId());
            usage.setGroupCode(task.getGroupCode());
            usage.setChannelId(task.getChannelId());
            usage.setSuccess(success);
            usage.setImageCount(task.getImageCount() == null ? 1 : task.getImageCount());
            usage.setBizAmount(BigDecimal.ZERO);
            usage.setCostAmount(BigDecimal.ZERO);
            usage.setErrorKind(errorKind);
            usage.setCreatedAt(LocalDateTime.now());
            usageLogMapper.insert(usage);
        } catch (Exception e) {
            log.error("用量流水写入失败（不影响任务终态）: taskId={}", task.getTaskId(), e);
        }
    }
}
