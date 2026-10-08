package com.drawbridge.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.entity.TaskAttempt;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.domain.mapper.TaskAttemptMapper;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据保留：按设置页"任务保留天数"清理终态任务与其尝试流水（用量流水保留供对账）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionCleaner {

    private final GenerateTaskMapper taskMapper;
    private final TaskAttemptMapper attemptMapper;
    private final SystemConfigService config;

    @Scheduled(cron = "0 23 * * * *")
    public void clean() {
        try {
            if (config.getInt(ConfigKeys.RETENTION_CLEANUP_ENABLED) != 1) {
                return;
            }
            LocalDateTime cutoff = LocalDateTime.now().minusDays(config.getInt(ConfigKeys.RETENTION_TASK_DAYS));
            List<GenerateTask> expired = taskMapper.selectList(Wrappers.<GenerateTask>lambdaQuery()
                    .select(GenerateTask::getId)
                    .in(GenerateTask::getStatus, TaskStatus.SUCCEEDED.name(), TaskStatus.FAILED.name())
                    .lt(GenerateTask::getFinishTime, cutoff)
                    .last("limit 500"));
            if (expired.isEmpty()) {
                return;
            }
            List<Long> ids = expired.stream().map(GenerateTask::getId).toList();
            attemptMapper.delete(Wrappers.<TaskAttempt>lambdaQuery().in(TaskAttempt::getTaskId, ids));
            taskMapper.deleteByIds(ids);
            log.info("过期任务清理: {} 条", ids.size());
        } catch (Exception e) {
            log.error("过期任务清理异常", e);
        }
    }
}
