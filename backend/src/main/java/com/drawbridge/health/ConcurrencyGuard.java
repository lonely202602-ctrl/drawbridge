package com.drawbridge.health;

import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 上游调用并发闸：全局 + 每渠道两层（信号量语义的原子计数实现，阈值热更新即时生效）。
 * tryAcquire 在预算时间内等待；拿不到返回 false（路由链路换下一候选）。
 */
@Component
public class ConcurrencyGuard {

    private final SystemConfigService config;
    private final AtomicInteger global = new AtomicInteger();
    private final Map<Long, AtomicInteger> byChannel = new ConcurrentHashMap<>();

    public ConcurrencyGuard(SystemConfigService config) {
        this.config = config;
    }

    public boolean tryAcquire(Long channelId, int channelMax, long waitMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        int globalMax = config.getInt(ConfigKeys.ROUTE_GLOBAL_CONCURRENCY);
        AtomicInteger channelCounter = byChannel.computeIfAbsent(channelId, k -> new AtomicInteger());
        do {
            if (global.incrementAndGet() <= globalMax) {
                if (channelCounter.incrementAndGet() <= effectiveMax(channelMax)) {
                    return true;
                }
                channelCounter.decrementAndGet();
            }
            global.decrementAndGet();
            sleepQuietly(20);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    public void release(Long channelId) {
        AtomicInteger channelCounter = byChannel.get(channelId);
        if (channelCounter != null) {
            channelCounter.decrementAndGet();
        }
        global.decrementAndGet();
    }

    public int globalInflight() {
        return global.get();
    }

    private int effectiveMax(Integer channelMax) {
        return channelMax == null || channelMax <= 0 ? 5 : channelMax;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
