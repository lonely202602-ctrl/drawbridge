package com.drawbridge.health;

import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道熔断器（被动统计 + 半开探测，阈值来自设置页热更新）：
 * 滑窗内失败率超阈值且样本数足够 → 熔断 openMs → 半开放行探测 → 连续 N 次成功恢复。
 */
@Component
public class ChannelHealthTracker {

    public static final int NORMAL = 1;
    public static final int OPEN = 2;
    public static final int HALF_OPEN = 3;

    private final SystemConfigService config;
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    public ChannelHealthTracker(SystemConfigService config) {
        this.config = config;
    }

    public synchronized void recordSuccess(Long channelId) {
        Window w = window(channelId);
        if (w.halfOpen) {
            w.halfOpenSuccesses++;
            if (w.halfOpenSuccesses >= config.getInt(ConfigKeys.BREAKER_HALF_OPEN_SUCCESS)) {
                w.halfOpen = false;
                w.openUntil = 0;
                w.halfOpenSuccesses = 0;
                w.events.clear();
            }
            return;
        }
        w.events.add(new Event(true, now()));
        prune(w);
    }

    public synchronized void recordFailure(Long channelId) {
        Window w = window(channelId);
        if (w.halfOpen) {
            // 半开探测失败：重新熔断
            w.openUntil = now() + config.getLong(ConfigKeys.BREAKER_OPEN_MS);
            w.halfOpen = false;
            w.halfOpenSuccesses = 0;
            return;
        }
        w.events.add(new Event(false, now()));
        prune(w);
        tripIfExceeded(channelId, w);
    }

    /** OPEN 返回 false；熔断到期自动转半开并放行探测。 */
    public synchronized boolean isAvailable(Long channelId) {
        Window w = window(channelId);
        if (w.openUntil > 0) {
            if (now() < w.openUntil) {
                return false;
            }
            if (!w.halfOpen) {
                w.halfOpen = true;
                w.halfOpenSuccesses = 0;
            }
            return true;
        }
        return true;
    }

    public synchronized int healthState(Long channelId) {
        Window w = window(channelId);
        if (w.openUntil > now()) {
            return OPEN;
        }
        if (w.halfOpen) {
            return HALF_OPEN;
        }
        return NORMAL;
    }

    private void tripIfExceeded(Long channelId, Window w) {
        long minSamples = Math.max(1, config.getInt(ConfigKeys.BREAKER_MIN_SAMPLES));
        if (w.events.size() >= minSamples) {
            long failures = w.events.stream().filter(e -> !e.success()).count();
            double rate = (double) failures / w.events.size();
            if (rate > config.getDouble(ConfigKeys.BREAKER_FAILURE_RATE)) {
                w.openUntil = now() + config.getLong(ConfigKeys.BREAKER_OPEN_MS);
                w.events.clear();
            }
        }
    }

    private void prune(Window w) {
        long cutoff = now() - config.getLong(ConfigKeys.BREAKER_WINDOW_MS);
        while (!w.events.isEmpty() && w.events.getFirst().ts() < cutoff) {
            w.events.pollFirst();
        }
    }

    private Window window(Long channelId) {
        return windows.computeIfAbsent(channelId, k -> new Window());
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private record Event(boolean success, long ts) {
    }

    private static final class Window {
        final Deque<Event> events = new ArrayDeque<>();
        long openUntil = 0;
        boolean halfOpen = false;
        int halfOpenSuccesses = 0;
    }
}
