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
 * 路由引擎：分组 → 有序候选序列（纯函数，评估不产生副作用）。
 * 过滤：分组停用、成员停用、渠道停用/自动停用、熔断 OPEN、429 冷却中；
 * 排序：POLLING 轮转 / RANDOM 打乱 / FAILOVER·IDLE_FIRST 按 sortOrder / WEIGHTED 加权随机。
 *
 * v0.4 变化：
 * - candidatesFor 不再推进轮转记忆（排队任务每秒评估一次，副作用推进会让轮转空转）；
 *   轮转记忆只在派发器领取成功后经 recordServed 推进，记实际选中的成员。
 * - candidateState 把"无候选"拆成两种：结构性（分组停用/无成员/渠道全停用 → 立即判失败）
 *   与暂时性（熔断/冷却 → 留队列等恢复），调用方据此决定"失败"还是"等"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteEngine {

    private final ChannelConfigService configService;
    private final ChannelHealthTracker healthTracker;
    private final CooldownRegistry cooldownRegistry;
    /** 轮询记忆：分组 → 上次实际选中的成员 id（仅在领取成功时推进；分组数据变更时重置） */
    private final Map<Long, Long> lastServedByGroup = new ConcurrentHashMap<>();

    /** 分组数据（成员/顺序/策略）变更后调用：清空轮询记忆，下次从新列表第 1 位开始。 */
    public void resetPolling(Long groupId) {
        lastServedByGroup.remove(groupId);
    }

    /** 领取成功后由派发器调用：推进轮询记忆到实际选中的成员。 */
    public void recordServed(Long groupId, Long memberId) {
        lastServedByGroup.put(groupId, memberId);
    }

    /** 候选可用性结论：OK 有候选 / STRUCTURAL_DOWN 结构性不可用（该判失败）/ TEMPORARY_DOWN 暂时性（该等待）。 */
    public enum Availability {OK, STRUCTURAL_DOWN, TEMPORARY_DOWN}

    public record GroupRouting(List<Candidate> candidates, Availability state, RouteStrategy strategy,
                               Long groupId) {
    }

    public GroupRouting candidateState(String groupCode) {
        ModelGroup group = configService.groupByCode(groupCode);
        if (group == null || group.getEnabled() == null || group.getEnabled() != 1) {
            return new GroupRouting(List.of(), Availability.STRUCTURAL_DOWN, RouteStrategy.FAILOVER, null);
        }
        List<GroupMember> members = configService.current().membersByGroupId()
                .getOrDefault(group.getId(), List.of()).stream()
                .filter(m -> m.getEnabled() != null && m.getEnabled() == 1)
                .toList();
        if (members.isEmpty()) {
            return new GroupRouting(List.of(), Availability.STRUCTURAL_DOWN, parseStrategy(group.getStrategy()),
                    group.getId());
        }

        // 结构层：渠道存在性/启停（渠道被删、停用、余额关停都是结构性的——等不会变好）
        // 暂时层：熔断开启、429 冷却（会自己恢复，该等）
        List<Candidate> structural = new ArrayList<>();
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
            structural.add(new Candidate(channel, member));
            if (!healthTracker.isAvailable(channel.getId())) {
                continue;
            }
            if (cooldownRegistry.isCooling(channel.getId())) {
                continue;
            }
            available.add(new Candidate(channel, member));
        }
        if (structural.isEmpty()) {
            return new GroupRouting(List.of(), Availability.STRUCTURAL_DOWN, parseStrategy(group.getStrategy()),
                    group.getId());
        }
        if (available.isEmpty()) {
            return new GroupRouting(List.of(), Availability.TEMPORARY_DOWN, parseStrategy(group.getStrategy()),
                    group.getId());
        }

        // 半开渠道垫底：探测期不与正常渠道平起平坐，病没好时先让它接最少的量。
        // 但停滞超过 half_open_idle_ms 的半开不再垫底——否则热渠道健康时它永远分不到探测流量，
        // 永远恢复不了；回到正常位接探测流量不破坏保护（没好会被打失败重新熔断）
        List<Candidate> hot = new ArrayList<>();
        List<Candidate> probing = new ArrayList<>();
        for (Candidate c : available) {
            boolean halfOpen = healthTracker.healthState(c.channel().getId()) == ChannelHealthTracker.HALF_OPEN;
            boolean bottomed = halfOpen && !healthTracker.halfOpenExpired(c.channel().getId());
            (bottomed ? probing : hot).add(c);
        }
        RouteStrategy strategy = parseStrategy(group.getStrategy());
        List<Candidate> ordered = new ArrayList<>(order(strategy, hot, group.getId()));
        ordered.addAll(order(strategy, probing, group.getId()));
        return new GroupRouting(List.copyOf(ordered), Availability.OK, strategy, group.getId());
    }

    /** 兼容入口：只要有序候选（等价于 candidateState().candidates()）。 */
    public List<Candidate> candidatesFor(String groupCode) {
        return candidateState(groupCode).candidates();
    }

    private List<Candidate> order(RouteStrategy strategy, List<Candidate> candidates, Long groupId) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        return switch (strategy) {
            case FAILOVER, IDLE_FIRST -> candidates;
            case RANDOM -> shuffle(candidates);
            case POLLING -> pollRotate(candidates, groupId);
            case WEIGHTED -> weightedShuffle(candidates);
        };
    }

    /**
     * 轮询（按成员身份轮转）：记住分组上次选中的成员，本次从它在序列中的下一个开始。
     * 管理员手动调整成员顺序不会打断轮转节奏。只读，不推进。
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
