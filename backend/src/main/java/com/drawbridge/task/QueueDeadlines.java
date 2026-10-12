package com.drawbridge.task;

import java.time.LocalDateTime;

/**
 * 任务双死线（列 expires_at 的双语义，与 next_poll_time 双语义同风格）：
 *   SUBMITTED = 排队死线（createdAt + task.queue_max_wait_ms；0=不限，给 100 年占位）——
 *               兜底清扫①对 SUBMITTED 的 expires 巡检直接生效，超时判 QUEUE_WAIT_TIMEOUT；
 *   RUNNING   = 执行死线（领取时刻 + task.max_lifetime_ms，每次领取重置）——超时判 TIMEOUT。
 */
public final class QueueDeadlines {

    private QueueDeadlines() {
    }

    public static LocalDateTime queueDeadline(LocalDateTime createdAt, long queueMaxWaitMs) {
        LocalDateTime base = createdAt == null ? LocalDateTime.now() : createdAt;
        return queueMaxWaitMs > 0 ? base.plusNanos(queueMaxWaitMs * 1_000_000) : base.plusYears(100);
    }

    public static LocalDateTime executionDeadline(LocalDateTime now, long lifetimeMs) {
        return now.plusNanos(Math.max(1_000, lifetimeMs) * 1_000_000);
    }
}
