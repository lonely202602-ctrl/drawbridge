package com.drawbridge.openapi;

import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务方令牌限流（滑动窗口计数，内存实现；阈值设置页可调）。
 */
@Component
public class TokenRateLimiter {

    private final SystemConfigService config;
    private final Map<Long, Deque<Long>> windows = new ConcurrentHashMap<>();

    public TokenRateLimiter(SystemConfigService config) {
        this.config = config;
    }

    public boolean allow(Long tokenId) {
        int limit = config.getInt(ConfigKeys.OPENAPI_RATE_PER_MIN);
        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(tokenId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.getFirst() < now - 60_000) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
