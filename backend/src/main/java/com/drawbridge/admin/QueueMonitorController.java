package com.drawbridge.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.common.ApiResponse;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.entity.ModelGroup;
import com.drawbridge.domain.entity.UpstreamAccount;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.domain.mapper.UpstreamAccountMapper;
import com.drawbridge.health.AccountRegistry;
import com.drawbridge.health.ChannelHealthTracker;
import com.drawbridge.health.ChannelOpsService;
import com.drawbridge.health.ConcurrencyGuard;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import com.drawbridge.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端-队列监控：排队/并发/在跑全景（数据源纯 SQL + 进程内配置快照，无外部中间件）。
 *   每账号行：容量、在跑（RUNNING 行数）、空闲、绑定渠道及健康状态；
 *   每分组行：排队数（SUBMITTED）、最老排队等待时长；
 *   在跑名单 + 关停批次提示。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/queue-monitor")
public class QueueMonitorController {

    private final GenerateTaskMapper taskMapper;
    private final UpstreamAccountMapper accountMapper;
    private final ChannelConfigService configService;
    private final ConcurrencyGuard guard;
    private final AccountRegistry accountRegistry;
    private final ChannelOpsService channelOps;
    private final SystemConfigService config;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        LocalDateTime now = LocalDateTime.now();

        // ---- 账号行：容量 / 在跑 / 空闲 / 绑定渠道 ----
        Map<String, Integer> runningByAccount = guard.runningByAccount(
                accountMapper.selectList(null).stream().map(UpstreamAccount::getAccountKey).toList());
        Map<String, List<Channel>> channelsByAccount = new LinkedHashMap<>();
        for (Channel c : configService.current().channelsById().values()) {
            if (c.getAccountKey() != null) {
                channelsByAccount.computeIfAbsent(c.getAccountKey(), k -> new ArrayList<>()).add(c);
            }
        }
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (UpstreamAccount acc : accountMapper.selectList(null)) {
            Map<String, Object> row = new LinkedHashMap<>();
            int capacity = acc.getCapacity() == null ? 0 : acc.getCapacity();
            int running = runningByAccount.getOrDefault(acc.getAccountKey(), 0);
            row.put("accountKey", acc.getAccountKey());
            row.put("capacity", capacity);
            row.put("running", running);
            row.put("free", Math.max(0, capacity - running));
            row.put("paused", capacity <= 0);
            List<Map<String, Object>> channelRows = new ArrayList<>();
            for (Channel c : channelsByAccount.getOrDefault(acc.getAccountKey(), List.of())) {
                Map<String, Object> cr = new LinkedHashMap<>();
                cr.put("id", c.getId());
                cr.put("name", c.getName());
                cr.put("enabled", c.getEnabled());
                cr.put("status", c.getStatus());
                cr.put("statusReason", c.getStatusReason() == null ? "" : c.getStatusReason());
                cr.put("health", ChannelHealthTracker.NORMAL == (c.getStatus() == null
                        ? ChannelHealthTracker.NORMAL : c.getStatus()) ? "normal" : "degraded");
                channelRows.add(cr);
            }
            row.put("channels", channelRows);
            // 该账号有渠道被批量关停 → 提示可整批恢复
            row.put("pendingBatches", channelOps.pendingBatches(acc.getAccountKey()).stream()
                    .map(b -> {
                        Map<String, Object> br = new LinkedHashMap<String, Object>();
                        br.put("id", b.getId());
                        br.put("reason", b.getReason());
                        br.put("createdAt", b.getCreatedAt());
                        return br;
                    }).toList());
            accounts.add(row);
        }

        // ---- 分组行：排队数 / 最老排队 ----
        List<Map<String, Object>> groups = new ArrayList<>();
        for (ModelGroup g : configService.current().groupsByCode().values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupCode", g.getGroupCode());
            row.put("strategy", g.getStrategy() == null ? "" : g.getStrategy());
            row.put("enabled", g.getEnabled());
            List<GenerateTask> queued = taskMapper.selectList(Wrappers.<GenerateTask>lambdaQuery()
                    .select(GenerateTask::getId, GenerateTask::getCreatedAt)
                    .eq(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                    .eq(GenerateTask::getGroupCode, g.getGroupCode())
                    .orderByAsc(GenerateTask::getCreatedAt));
            row.put("queued", queued.size());
            row.put("oldestQueuedSeconds", queued.isEmpty() ? 0
                    : java.time.Duration.between(queued.getFirst().getCreatedAt(), now).getSeconds());
            groups.add(row);
        }

        // ---- 在跑名单（最近 50）----
        List<Map<String, Object>> running = taskMapper.selectList(Wrappers.<GenerateTask>lambdaQuery()
                        .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                        .orderByDesc(GenerateTask::getUpdatedAt)
                        .last("limit 50"))
                .stream().map(t -> {
                    Map<String, Object> r = new LinkedHashMap<String, Object>();
                    r.put("taskId", t.getTaskId());
                    r.put("groupCode", t.getGroupCode());
                    r.put("channelId", t.getChannelId());
                    r.put("hasUpstreamTaskId", t.getUpstreamTaskId() != null);
                    r.put("updatedAt", t.getUpdatedAt());
                    return r;
                }).toList();

        long queuedTotal = taskMapper.selectCount(Wrappers.<GenerateTask>lambdaQuery()
                .eq(GenerateTask::getStatus, TaskStatus.SUBMITTED.name()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totals", Map.of(
                "queued", queuedTotal,
                "running", guard.runningTotal(),
                "globalMax", config.getInt(ConfigKeys.ROUTE_GLOBAL_CONCURRENCY)));
        result.put("config", Map.of(
                "queueMaxWaitMinutes", config.getLong(ConfigKeys.TASK_QUEUE_MAX_WAIT_MS) / 60_000,
                "tokenMaxQueued", config.getInt(ConfigKeys.TOKEN_MAX_QUEUED)));
        result.put("accounts", accounts);
        result.put("groups", groups);
        result.put("runningTasks", running);
        return ApiResponse.ok(result);
    }
}
