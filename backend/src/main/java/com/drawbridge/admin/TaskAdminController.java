package com.drawbridge.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.drawbridge.common.ApiResponse;
import com.drawbridge.common.BizException;
import com.drawbridge.common.ErrorCode;
import com.drawbridge.common.PageResult;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.entity.TaskAttempt;
import com.drawbridge.domain.entity.UsageLog;
import com.drawbridge.domain.mapper.ChannelMapper;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.domain.mapper.TaskAttemptMapper;
import com.drawbridge.domain.mapper.UsageLogMapper;
import com.drawbridge.health.ChannelHealthTracker;
import com.drawbridge.health.CooldownRegistry;
import com.drawbridge.health.ConcurrencyGuard;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端-任务与监控：任务列表/详情（重试时间线 + 双向请求体 + 结果）、仪表盘。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class TaskAdminController {

    private final GenerateTaskMapper taskMapper;
    private final TaskAttemptMapper attemptMapper;
    private final UsageLogMapper usageLogMapper;
    private final ChannelMapper channelMapper;
    private final ChannelConfigService configService;
    private final ChannelHealthTracker healthTracker;
    private final CooldownRegistry cooldownRegistry;
    private final ConcurrencyGuard concurrencyGuard;

    @GetMapping("/tasks")
    public ApiResponse<PageResult<Map<String, Object>>> tasks(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String groupCode,
            @RequestParam(required = false) String taskId) {
        var wrapper = Wrappers.<GenerateTask>lambdaQuery()
                .orderByDesc(GenerateTask::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(GenerateTask::getStatus, status);
        }
        if (groupCode != null && !groupCode.isBlank()) {
            wrapper.eq(GenerateTask::getGroupCode, groupCode);
        }
        if (taskId != null && !taskId.isBlank()) {
            wrapper.like(GenerateTask::getTaskId, taskId);
        }
        Page<GenerateTask> result = taskMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        Map<Long, String> channelNames = channelNames();
        List<Map<String, Object>> records = result.getRecords().stream()
                .map(t -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", t.getId());
                    row.put("taskId", t.getTaskId());
                    row.put("groupCode", t.getGroupCode());
                    row.put("status", t.getStatus());
                    row.put("errorCode", t.getErrorCode());
                    row.put("errorMsg", t.getErrorMsg());
                    row.put("channelId", t.getChannelId());
                    row.put("channelName", t.getChannelId() == null ? null
                            : channelNames.get(t.getChannelId()));
                    row.put("upstreamTaskId", t.getUpstreamTaskId());
                    row.put("attemptCount", t.getAttemptCount());
                    row.put("pollCount", t.getPollCount());
                    row.put("freezeAmount", t.getFreezeAmount());
                    row.put("createdAt", t.getCreatedAt());
                    row.put("finishTime", t.getFinishTime());
                    return row;
                })
                .toList();
        return ApiResponse.ok(new PageResult<>(result.getTotal(), records));
    }

    /** 任务详情：任务全量 + 重试时间线（每次调了哪个上游、什么错）。 */
    @GetMapping("/tasks/detail/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        GenerateTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        Map<Long, Channel> channels = channelMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(Channel::getId, c -> c));
        List<Map<String, Object>> attempts = attemptMapper.selectList(Wrappers.<TaskAttempt>lambdaQuery()
                        .eq(TaskAttempt::getTaskId, id)
                        .orderByAsc(TaskAttempt::getAttemptNo))
                .stream()
                .map(a -> {
                    Channel c = channels.get(a.getChannelId());
                    Map<String, Object> row = new HashMap<>();
                    row.put("attemptNo", a.getAttemptNo());
                    row.put("phase", a.getPhase());
                    row.put("outcome", a.getOutcome());
                    row.put("channelId", a.getChannelId());
                    row.put("channelName", c == null ? "(已删除)" : c.getName());
                    row.put("upstreamModel", a.getUpstreamModel());
                    row.put("errorKind", a.getErrorKind());
                    row.put("httpStatus", a.getHttpStatus());
                    row.put("errorMsg", a.getErrorMsg());
                    row.put("costMs", a.getCostMs());
                    row.put("createdAt", a.getCreatedAt());
                    return row;
                })
                .toList();
        Map<String, Object> detail = new HashMap<>();
        detail.put("task", task);
        detail.put("attempts", attempts);
        detail.put("channelName", task.getChannelId() == null ? null
                : channelNames().get(task.getChannelId()));
        return ApiResponse.ok(detail);
    }

    /** 仪表盘：任务量/成功率/在途/最老待处理任务年龄/渠道健康。 */
    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dayStart = now.truncatedTo(ChronoUnit.DAYS);

        long todayTotal = taskMapper.selectCount(Wrappers.<GenerateTask>lambdaQuery()
                .ge(GenerateTask::getCreatedAt, dayStart));
        long todayFailed = taskMapper.selectCount(Wrappers.<GenerateTask>lambdaQuery()
                .ge(GenerateTask::getCreatedAt, dayStart)
                .eq(GenerateTask::getStatus, "FAILED"));
        long inflight = taskMapper.selectCount(Wrappers.<GenerateTask>lambdaQuery()
                .in(GenerateTask::getStatus, "SUBMITTED", "RUNNING"));
        GenerateTask oldest = taskMapper.selectOne(Wrappers.<GenerateTask>lambdaQuery()
                .in(GenerateTask::getStatus, "SUBMITTED", "RUNNING")
                .orderByAsc(GenerateTask::getCreatedAt)
                .last("limit 1"));
        long oldestPendingSec = oldest == null ? 0
                : ChronoUnit.SECONDS.between(oldest.getCreatedAt(), now);
        long costToday = usageLogMapper.selectCount(Wrappers.<UsageLog>lambdaQuery()
                .ge(UsageLog::getCreatedAt, dayStart));

        List<Map<String, Object>> channels = configService.current().channelsById().values().stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "name", c.getName(),
                        "protocol", c.getProtocol(),
                        "enabled", c.getEnabled(),
                        "status", c.getStatus(),
                        "statusReason", c.getStatusReason() == null ? "" : c.getStatusReason(),
                        "health", healthTracker.healthState(c.getId()),
                        "cooling", cooldownRegistry.isCooling(c.getId())))
                .toList();

        Map<String, Object> data = new HashMap<>();
        data.put("todayTotal", todayTotal);
        data.put("todayFailed", todayFailed);
        data.put("inflight", inflight);
        data.put("oldestPendingSec", oldestPendingSec);
        data.put("usageToday", costToday);
        data.put("globalUpstreamInflight", concurrencyGuard.globalInflight());
        data.put("channels", channels);
        return ApiResponse.ok(data);
    }

    private Map<Long, String> channelNames() {
        return channelMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(Channel::getId, Channel::getName));
    }
}
