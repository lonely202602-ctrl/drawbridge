package com.drawbridge.health;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位账本（DB 即真相，无内存计数层）：
 *   队列 = SUBMITTED 任务行；槽位 = 上游账号下 RUNNING 任务行（channel_id 绑定即占用）。
 *   占用数 = 该账号渠道下 RUNNING 行的 COUNT；释放 = TaskFinisher 终态写（行离开 RUNNING，天然出账）。
 *
 * 使用规约（防超发的关键，见派发器）：
 *   - runningByAccount 的快照只用于"选路排序"，绝不作为领取依据；
 *   - 领取判断必须在全局领取锁内用 runningOfAccount 重新 COUNT；
 *   - 领取 UPDATE 提交在放锁之前（领取路径禁止外层事务）。
 * 全部走 idx_status_nextpoll / 主键级索引，RUNNING 行数很小，COUNT 毫秒级。
 */
@Component
@RequiredArgsConstructor
public class ConcurrencyGuard {

    private final GenerateTaskMapper taskMapper;
    private final ChannelConfigService configService;
    private final AccountRegistry accountRegistry;

    /** 各账号在跑数快照（选路用）。键=accountKey，值=RUNNING 行数。 */
    public Map<String, Integer> runningByAccount(Collection<String> accountKeys) {
        Map<String, Integer> result = new HashMap<>();
        if (accountKeys == null || accountKeys.isEmpty()) {
            return result;
        }
        // 账号 → 其渠道 id 列表（配置快照，不查库）
        Map<Long, String> channelAccount = new HashMap<>();
        for (Channel2 c : channelsOf(accountKeys)) {
            channelAccount.put(c.id(), c.accountKey());
        }
        if (channelAccount.isEmpty()) {
            return result;
        }
        List<Map<String, Object>> rows = taskMapper.selectMaps(Wrappers.<GenerateTask>query()
                .select("channel_id", "COUNT(*) AS cnt")
                .eq("status", TaskStatus.RUNNING.name())
                .in("channel_id", channelAccount.keySet())
                .groupBy("channel_id"));
        for (Map<String, Object> row : rows) {
            Long channelId = ((Number) row.get("channel_id")).longValue();
            int cnt = ((Number) row.get("cnt")).intValue();
            String ak = channelAccount.get(channelId);
            if (ak != null) {
                result.merge(ak, cnt, Integer::sum);
            }
        }
        return result;
    }

    /** 单账号在跑数（锁内领取判断用，必须现查）。 */
    public int runningOfAccount(String accountKey) {
        List<Channel2> channels = channelsOf(List.of(accountKey));
        if (channels.isEmpty()) {
            return 0;
        }
        Long cnt = taskMapper.selectCount(Wrappers.<GenerateTask>lambdaQuery()
                .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                .in(GenerateTask::getChannelId, channels.stream().map(Channel2::id).toList()));
        return cnt == null ? 0 : cnt.intValue();
    }

    /** 全网在跑总数（全局上限判断 + 仪表盘）。 */
    public int runningTotal() {
        Long cnt = taskMapper.selectCount(Wrappers.<GenerateTask>lambdaQuery()
                .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name()));
        return cnt == null ? 0 : cnt.intValue();
    }

    /** 仪表盘兼容入口（语义已变为全网在途任务数）。 */
    public int globalInflight() {
        return runningTotal();
    }

    /** 账号容量（0=暂停派发）。 */
    public int capacityOf(String accountKey) {
        return accountRegistry.capacityOf(accountKey);
    }

    private record Channel2(Long id, String accountKey) {
    }

    private List<Channel2> channelsOf(Collection<String> accountKeys) {
        var snapshot = configService.current();
        return snapshot.channelsById().values().stream()
                .filter(c -> c.getAccountKey() != null && accountKeys.contains(c.getAccountKey()))
                .map(c -> new Channel2(c.getId(), c.getAccountKey()))
                .toList();
    }
}
