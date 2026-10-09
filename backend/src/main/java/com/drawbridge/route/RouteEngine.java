package com.drawbridge.route;

import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GroupMember;
import com.drawbridge.domain.entity.ModelGroup;
import com.drawbridge.health.ChannelHealthTracker;
import com.drawbridge.health.CooldownRegistry;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 路由引擎：分组 → 可用候选序列。
 * 过滤：分组停用、成员停用、渠道停用/自动停用、熔断 OPEN、429 冷却中；
 * 排序：POLLING 轮转 / RANDOM 打乱 / FAILOVER 按 sortOrder / WEIGHTED 按 weight 加权随机。
 * 返回的是"有序候选列表"，提交链路按序尝试（天然实现故障转移）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteEngine {

    private final ChannelConfigService configService;
    private final ChannelHealthTracker healthTracker;
    private final CooldownRegistry cooldownRegistry;
    /** 轮询记忆：分组 → 上次实际选中的成员 id（按成员身份轮转；分组数据变更时重置，重新生效） */
    private final Map<Long, Long> lastServedByGroup = new ConcurrentHashMap<>();

    /** 分组数据（成员/顺序/策略）变更后调用：清空轮询记忆，下次从新列表第 1 位开始。 */
    public void resetPolling(Long groupId) {
        lastServedByGroup.remove(groupId);
    }

    public List<Candidate> candidatesFor(String groupCode) {
        ModelGroup group = configService.groupByCode(groupCode);
        if (group == null || group.getEnabled() == null || group.getEnabled() != 1) {
            return List.of();
        }
        RouteStrategy strategy = parseStrategy(group.getStrategy());
        List<GroupMember> members = configService.current().membersByGroupId()
                .getOrDefault(group.getId(), List.of()).stream()
                .filter(m -> m.getEnabled() != null && m.getEnabled() == 1)
                .toList();
        if (members.isEmpty()) {
            return List.of();
        }

        List<Candidate> available = new ArrayList<>();
        for (GroupMember member : sortByOrder(members)) {
            Channel channel = configService.channel(member.getChannelId());
            if (channel == null || channel.getEnabled() == null || channel.getEnabled() != 1) {
                continue;
            }
            if (channel.getStatus() != null
                    && channel.getStatus() != ChannelHealthTracker.NORMAL
                    && channel.getStatus() != ChannelHealthTracker.HALF_OPEN) {
                continue;
            }
            if (!healthTracker.isAvailable(channel.getId())) {
                continue;
            }
            if (cooldownRegistry.isCooling(channel.getId())) {
                continue;
            }
            available.add(new Candidate(channel, member));
        }
        List<Candidate> ordered = order(strategy, available, group.getId());
        if (strategy == RouteStrategy.POLLING && !ordered.isEmpty()) {
            lastServedByGroup.put(group.getId(), ordered.getFirst().member().getId());
        }
        return ordered;
    }

    private List<Candidate> order(RouteStrategy strategy, List<Candidate> candidates, Long groupId) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        return switch (strategy) {
            case FAILOVER -> candidates;
            case RANDOM -> shuffle(candidates);
            case POLLING -> pollRotate(candidates, groupId);
            case WEIGHTED -> weightedShuffle(candidates);
        };
    }

    /**
     * 轮询（按成员身份轮转）：记住分组上次选中的成员，本次从它在序列中的下一个开始。
     * 管理员手动调整成员顺序不会打断轮转节奏。
     */
    private List<Candidate> pollRotate(List<Candidate> candidates, Long groupId) {
        Long lastId = lastServedByGroup.get(groupId);
        int n = candidates.size();
        int start = 0;
        if (lastId != null) {
            for (int i = 0; i < n; i++) {
                if (candidates.get(i).member().getId().equals(lastId)) {
                    start = (i + 1) % n;
                    break;
                }
            }
        }
        List<Candidate> copy = new ArrayList<>(candidates);
        java.util.Collections.rotate(copy, -start);
        return copy;
    }

    private List<GroupMember> sortByOrder(List<GroupMember> members) {
        return members.stream()
                .sorted(Comparator.comparing(GroupMember::getSortOrder,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(GroupMember::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private List<Candidate> shuffle(List<Candidate> candidates) {
        List<Candidate> copy = new ArrayList<>(candidates);
        java.util.Collections.shuffle(copy);
        return copy;
    }

    private List<Candidate> rotate(List<Candidate> candidates, int offset) {
        List<Candidate> copy = new ArrayList<>(candidates);
        java.util.Collections.rotate(copy, -offset);
        return copy;
    }

    /** Efraimidis-Spirakis 加权随机：key = ln(U) / weight，取 key 降序。 */
    private List<Candidate> weightedShuffle(List<Candidate> candidates) {
        record Keyed(Candidate candidate, double key) {
        }
        List<Keyed> keyed = new ArrayList<>();
        for (Candidate c : candidates) {
            int weight = c.member().getWeight() == null || c.member().getWeight() < 1
                    ? 1 : c.member().getWeight();
            double u = Math.max(ThreadLocalRandom.current().nextDouble(), Double.MIN_VALUE);
            keyed.add(new Keyed(c, Math.log(u) / weight));
        }
        keyed.sort(java.util.Comparator.comparingDouble(Keyed::key).reversed());
        return keyed.stream().map(Keyed::candidate).toList();
    }

    private RouteStrategy parseStrategy(String strategy) {
        try {
            return RouteStrategy.valueOf(strategy);
        } catch (Exception e) {
            log.warn("未知路由策略 {}，按 FAILOVER 处理", strategy);
            return RouteStrategy.FAILOVER;
        }
    }

    public record Candidate(Channel channel, GroupMember member) {
    }
}
