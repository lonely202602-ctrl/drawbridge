package com.drawbridge.infrastructure.config;

import lombok.Getter;

/**
 * 运营参数键 + 代码内置默认值（DB 可覆盖，设置页热更新）。
 */
@Getter
public enum ConfigKeys {

    ROUTE_MAX_CHANNEL_ATTEMPTS("route.max_channel_attempts", "4", "单任务最多尝试的渠道数（跨排队重入累计）"),
    ROUTE_GLOBAL_CONCURRENCY("route.global_concurrency", "256", "全网在途任务数上限（RUNNING 行总数，兜底防失控）"),

    BREAKER_WINDOW_MS("breaker.window_ms", "60000", "熔断统计滑窗（毫秒）"),
    BREAKER_MIN_SAMPLES("breaker.min_samples", "20", "触发熔断的最小样本数"),
    BREAKER_FAILURE_RATE("breaker.failure_rate", "0.5", "熔断失败率阈值（0-1）"),
    BREAKER_OPEN_MS("breaker.open_ms", "60000", "熔断持续时长（毫秒）"),
    BREAKER_HALF_OPEN_SUCCESS("breaker.half_open_success", "3", "半开恢复所需连续成功次数"),
    BREAKER_HALF_OPEN_IDLE_MS("breaker.half_open_idle_ms", "120000", "半开停滞多久后不再垫底（回正常排队位置接探测流量，毫秒）"),

    RATE_LIMIT_COOLDOWN_MS("rate_limit.cooldown_ms", "30000", "上游 429 后的冷却时长（毫秒）"),

    SUBMIT_TIMEOUT_MS("submit.timeout_ms", "120000", "上游提交 HTTP 超时（毫秒）"),

    POLL_INITIAL_INTERVAL_MS("poll.initial_interval_ms", "5000", "轮询初始间隔（毫秒）"),
    POLL_MAX_INTERVAL_MS("poll.max_interval_ms", "20000", "轮询退避上限（毫秒）"),
    POLL_BATCH_SIZE("poll.batch_size", "100", "单批扫描任务数"),
    POLL_LEASE_MS("poll.lease_ms", "15000", "轮询领取租约时长（毫秒）"),
    SUBMIT_LEASE_MS("submit.lease_ms", "90000", "提交链路领取租约时长（毫秒），需大于重试时间预算"),

    TASK_MAX_LIFETIME_MS("task.max_lifetime_ms", "900000", "任务执行生命周期（毫秒），领取渠道时起算，超时判失败"),
    TASK_QUEUE_MAX_WAIT_MS("task.queue_max_wait_ms", "1800000", "排队等待上限（毫秒），从提交时刻起算，超时判 QUEUE_WAIT_TIMEOUT；0=不限"),
    TASK_STALE_MINUTES("task.stale_minutes", "10", "任务停滞多久后由兜底清扫重扫（分钟）"),

    TOKEN_MAX_QUEUED("token.max_queued", "20", "单令牌最大排队任务数（提交时校验，超出即拒），0=不限"),

    RETENTION_TASK_DAYS("retention.task_days", "7", "任务记录保留天数"),
    RETENTION_BODY_MAX_BYTES("retention.body_max_bytes", "32768", "上游请求存档截断上限（字节）；只影响存档展示，实际按 ≤65KB 钳制，非法值回退 32KB"),
    RETENTION_CLEANUP_ENABLED("retention.cleanup_enabled", "1", "是否自动清理过期任务记录"),

    OPENAPI_RATE_PER_MIN("openapi.rate_per_min", "120", "业务方令牌每分钟提交上限"),
    OPENAPI_QUERY_RATE_PER_MIN("openapi.query_rate_per_min", "600", "业务方令牌每分钟查询上限（与提交分开计数）");

    private final String key;
    private final String defaultValue;
    private final String description;

    ConfigKeys(String key, String defaultValue, String description) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    /** 设置页展示用：键 → 中文描述；未知键回退为键名本身。 */
    public static String descriptionOf(String key) {
        for (ConfigKeys k : values()) {
            if (k.key.equals(key)) {
                return k.description;
            }
        }
        return key;
    }
}
