package com.drawbridge.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.entity.ModelGroup;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.health.AccountRegistry;
import com.drawbridge.health.ConcurrencyGuard;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import com.drawbridge.route.RouteEngine;
import com.drawbridge.route.RouteStrategy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 提交派发器（DB 当队列）：队列 = SUBMITTED 任务行；槽位 = 上游账号下 RUNNING 行（channel_id 绑定即占用）。
 *
 * 派发规约：
 * - 公平：同组内严格按 created_at 升序逐个领取（队头不满不出队）；提交快路径（kick）只在
 *   "该任务是其分组最早的排队任务"时才直接派发，否则交给派发器按序处理——新提交不插队。
 * - 无批次饿死：每轮先算"哪些账号有空槽"，逐分组取最老队头，满了的账号囤再多任务也遮不住别组。
 * - 防超发：选路用 guard.runningByAccount 快照；领取在 DispatchLock 内用 runningOfAccount 现查
 *   （SlotClaims），UPDATE 放锁前提交。
 * - 策略语义：FAILOVER 只看队头首选，饱和 → 等不跳槽（出错才切换）；
 *   POLLING/RANDOM/WEIGHTED/IDLE_FIRST 按序跳过饱和候选取第一个有空槽的。全满 → 留队列等下一轮。
 * - 轮转记忆只在领取成功时推进（recordServed 记实际选中成员），评估不产生副作用。
 * - 结构性无候选（分组停用/无成员/渠道全停用）→ 排队任务立即判 NO_AVAILABLE_CHANNEL；
 *   暂时性（熔断/429 冷却）→ 留队列等恢复。
 * - 补位即时性：TaskFinisher 写终态后发 TaskSettledEvent，本类监听立即开新一轮（不等 1 秒节拍）。
 *
 * 回退守则与 v0.3 相同：只有确定持有任务才回退；回退恢复排队死线并清渠道绑定，
 * rerun 退避 5s→15s→45s→135s→240s，5 次判 INTERNAL_ERROR（上游已受理的不判，交轮询/清扫）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmitExecutor {

    private static final int MAX_AUTO_RERUNS = 5;
    private static final int STRUCTURAL_FAIL_BATCH = 50;

    private final GenerateTaskMapper taskMapper;
    private final SubmitChain chain;
    private final TaskFinisher finisher;
    private final SystemConfigService config;
    private final ChannelConfigService configService;
    private final ConcurrencyGuard guard;
    private final RouteEngine routeEngine;
    private final SlotClaims slotClaims;

    private final ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor();
    private final Object dispatchSignal = new Object();
    private volatile boolean dispatcherRunning = true;
    private Thread dispatcherThread;

    @PostConstruct
    public void start() {
        dispatcherThread = Thread.ofPlatform().name("task-dispatcher").start(this::dispatcherLoop);
        log.info("任务派发器已启动（1s 节拍 + 终态唤醒补位）");
    }

    private void dispatcherLoop() {
        while (dispatcherRunning) {
            try {
                scanDue();
            } catch (Throwable t) {
                log.error("派发轮异常（下一轮重试）", t);
            }
            synchronized (dispatchSignal) {
                if (dispatcherRunning) {
                    try {
                        dispatchSignal.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /** 终态唤醒：槽位释放后立即补位。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTaskSettled(TaskSettledEvent event) {
        nudge();
    }

    public void nudge() {
        synchronized (dispatchSignal) {
            dispatchSignal.notifyAll();
        }
    }

    @PreDestroy
    public void shutdown() {
        dispatcherRunning = false;
        synchronized (dispatchSignal) {
            dispatchSignal.notifyAll();
        }
        if (dispatcherThread != null) {
            dispatcherThread.interrupt();
        }
        vexec.shutdown();
        try {
            if (!vexec.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("优雅停机超时（30s），强制中断在途提交");
                vexec.shutdownNow();
            } else {
                log.info("在途提交已全部收尾，派发器停止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            vexec.shutdownNow();
        }
    }

    /** 一轮派发（1 秒节拍 / 终态唤醒 / 测试直调）。 */
    public void scanDue() {
        // 结构性死亡先判：这些任务等不来渠道，立即给明确失败
        for (ModelGroup group : enabledGroups()) {
            if (routeEngine.candidateState(group.getGroupCode()).state()
                    == RouteEngine.Availability.STRUCTURAL_DOWN) {
                failQueuedOfGroup(group.getGroupCode());
            }
        }
        int globalMax = config.getInt(ConfigKeys.ROUTE_GLOBAL_CONCURRENCY);
        Map<String, Integer> freeByAccount = freeSlotsByAccount();
        for (ModelGroup group : enabledGroups()) {
            RouteEngine.GroupRouting st = routeEngine.candidateState(group.getGroupCode());
            if (st.state() != RouteEngine.Availability.OK) {
                continue; // 结构性已在上面判失败；暂时性（熔断/冷却）留队列等
            }
            while (guard.runningTotal() < globalMax) {
                GenerateTask oldest = oldestQueued(group.getGroupCode());
                if (oldest == null) {
                    break;
                }
                RouteEngine.Candidate pick = selectCandidate(st, freeByAccount);
                if (pick == null) {
                    break; // 本组候选全满：队头原地等，绝不出队更不失败
                }
                SlotClaims.ClaimResult r = slotClaims.claimSubmitted(oldest.getId(), pick.channel());
                switch (r) {
                    case CLAIMED -> {
                        if (st.strategy() == RouteStrategy.POLLING) {
                            routeEngine.recordServed(st.groupId(), pick.member().getId());
                        }
                        freeByAccount.merge(pick.channel().getAccountKey(), -1, Integer::sum);
                        Long taskId = oldest.getId();
                        vexec.submit(() -> runChainSafely(taskId));
                    }
                    case STALE -> {
                        // 任务被并发领走/已终态：同组看下一条
                    }
                    case NO_SLOT -> {
                        // 锁内复查发现该账号已满：修正快照，本组本轮结束
                        freeByAccount.put(pick.channel().getAccountKey(), 0);
                    }
                }
                if (r == SlotClaims.ClaimResult.NO_SLOT) {
                    break;
                }
            }
        }
    }

    /** 提交接口落库后直接调用（快路径）。公平前置：同组已有更早排队任务时不插队。 */
    public void kick(Long taskId) {
        vexec.submit(() -> {
            try {
                GenerateTask t = taskMapper.selectById(taskId);
                if (t == null || !TaskStatus.SUBMITTED.name().equals(t.getStatus())) {
                    return;
                }
                Long older = taskMapper.selectCount(Wrappers.<GenerateTask>lambdaQuery()
                        .eq(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                        .eq(GenerateTask::getGroupCode, t.getGroupCode())
                        .ne(GenerateTask::getId, taskId)
                        .lt(GenerateTask::getCreatedAt, t.getCreatedAt()));
                if (older != null && older > 0) {
                    return; // 不插队：派发器按 created_at 顺序处理
                }
                dispatchOne(t);
            } catch (Exception e) {
                log.warn("提交快路径派发失败（等扫描兜底）: taskId={}", taskId, e);
            }
        });
    }

    private void dispatchOne(GenerateTask t) {
        RouteEngine.GroupRouting st = routeEngine.candidateState(t.getGroupCode());
        if (st.state() == RouteEngine.Availability.STRUCTURAL_DOWN) {
            finisher.fail(t.getId(), "NO_AVAILABLE_CHANNEL", "该模型暂无可用上游渠道");
            return;
        }
        if (st.state() != RouteEngine.Availability.OK) {
            return;
        }
        RouteEngine.Candidate pick = selectCandidate(st, freeSlotsByAccount());
        if (pick == null) {
            return;
        }
        if (slotClaims.claimSubmitted(t.getId(), pick.channel()) == SlotClaims.ClaimResult.CLAIMED) {
            if (st.strategy() == RouteStrategy.POLLING) {
                routeEngine.recordServed(st.groupId(), pick.member().getId());
            }
            runChainSafely(t.getId());
        }
    }

    /** 策略选候选：FAILOVER 只看队头（饱和即等）；其余策略按序跳过饱和取第一个有空槽的。 */
    private RouteEngine.Candidate selectCandidate(RouteEngine.GroupRouting st,
                                                  Map<String, Integer> freeByAccount) {
        List<RouteEngine.Candidate> candidates = st.candidates();
        if (candidates.isEmpty()) {
            return null;
        }
        if (st.strategy() == RouteStrategy.FAILOVER) {
            RouteEngine.Candidate first = candidates.getFirst();
            String ak = first.channel().getAccountKey();
            if (ak == null || guard.capacityOf(ak) <= 0) {
                return null;
            }
            return freeByAccount.getOrDefault(ak, 0) > 0 ? first : null;
        }
        for (RouteEngine.Candidate c : candidates) {
            String ak = c.channel().getAccountKey();
            if (ak == null || guard.capacityOf(ak) <= 0) {
                continue; // 容量 0 = 暂停派发
            }
            if (freeByAccount.getOrDefault(ak, 0) > 0) {
                return c;
            }
        }
        return null;
    }

    /** 有空槽的账号集合（容量 - 快照在跑数 > 0）。仅选路用，领取以锁内现查为准。 */
    private Map<String, Integer> freeSlotsByAccount() {
        Set<String> keys = configService.current().channelsById().values().stream()
                .map(Channel::getAccountKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Integer> running = guard.runningByAccount(keys);
        Map<String, Integer> free = new HashMap<>();
        for (String key : keys) {
            int f = guard.capacityOf(key) - running.getOrDefault(key, 0);
            if (f > 0) {
                free.put(key, f);
            }
        }
        return free;
    }

    private GenerateTask oldestQueued(String groupCode) {
        return taskMapper.selectOne(Wrappers.<GenerateTask>lambdaQuery()
                .eq(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                .eq(GenerateTask::getGroupCode, groupCode)
                .le(GenerateTask::getNextPollTime, LocalDateTime.now())
                .orderByAsc(GenerateTask::getCreatedAt)
                .orderByAsc(GenerateTask::getId)
                .last("limit 1"));
    }

    private void failQueuedOfGroup(String groupCode) {
        List<GenerateTask> doomed = taskMapper.selectList(Wrappers.<GenerateTask>lambdaQuery()
                .eq(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                .eq(GenerateTask::getGroupCode, groupCode)
                .le(GenerateTask::getNextPollTime, LocalDateTime.now())
                .orderByAsc(GenerateTask::getCreatedAt)
                .last("limit " + STRUCTURAL_FAIL_BATCH));
        doomed.forEach(t -> finisher.fail(t.getId(), "NO_AVAILABLE_CHANNEL", "该模型暂无可用上游渠道"));
    }

    private List<ModelGroup> enabledGroups() {
        return configService.current().groupsByCode().values().stream()
                .filter(g -> g.getEnabled() != null && g.getEnabled() == 1)
                .sorted(Comparator.comparing(ModelGroup::getId))
                .toList();
    }

    private void runChainSafely(Long taskId) {
        try {
            chain.run(taskId);
        } catch (SubmissionAbortedException e) {
            log.info("提交在发请求前终止，任务回退重跑: taskId={}, reason={}", taskId, e.getMessage());
            rollbackClaim(taskId);
        } catch (Exception e) {
            log.error("提交链路异常，回退待重跑: taskId={}", taskId, e);
            rollbackClaim(taskId);
        }
    }

    /**
     * 灾难回退：仅当任务尚未受理上游（无上游任务号）时退回 SUBMITTED。
     * 退回时恢复排队死线（expires_at 双语义）并清渠道绑定（槽位出账）；
     * 排队死线已过 → 直接 QUEUE_WAIT_TIMEOUT 终态（不再无限续期）。
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
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime queueDeadline = QueueDeadlines.queueDeadline(t.getCreatedAt(),
                    config.getLong(ConfigKeys.TASK_QUEUE_MAX_WAIT_MS));
            if (now.isAfter(queueDeadline)) {
                finisher.fail(taskId, "QUEUE_WAIT_TIMEOUT", "排队等待超时（回退重试时已超过排队上限）");
                return;
            }
            taskMapper.update(null, Wrappers.<GenerateTask>lambdaUpdate()
                    .eq(GenerateTask::getId, taskId)
                    .eq(GenerateTask::getStatus, TaskStatus.RUNNING.name())
                    .isNull(GenerateTask::getUpstreamTaskId)
                    .set(GenerateTask::getStatus, TaskStatus.SUBMITTED.name())
                    .set(GenerateTask::getChannelId, null)
                    .set(GenerateTask::getRerunCount, reruns + 1)
                    .set(GenerateTask::getNextPollTime, now.plusNanos(backoffMs * 1_000_000))
                    .set(GenerateTask::getExpiresAt, queueDeadline)
                    .set(GenerateTask::getUpdatedAt, now));
        } catch (Exception inner) {
            log.error("回退失败，等待兜底清扫: taskId={}", taskId, inner);
        }
    }
}
