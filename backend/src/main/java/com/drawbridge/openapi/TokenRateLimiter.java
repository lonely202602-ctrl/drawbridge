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
 * 提交与查询分开计数：提交贵（落库+占上游），查询便宜但会被轮询放大，各自护栏。
 */
@Component
public class TokenRateLimiter {

    private final SystemConfigService config;
    /** 窗口键 = tokenId + 限流维度，提交/查询互不挤占 */
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public TokenRateLimiter(SystemConfigService config) {
        this.config = config;
    }

    /** 提交限流（沿用 openapi.rate_per_min）。 */
    public boolean allowSubmit(Long tokenId) {
        return allow(tokenId, ConfigKeys.OPENAPI_RATE_PER_MIN);
    }

    /** 查询限流（openapi.query_rate_per_min，默认更宽：轮询是文档建议的合法行为）。 */
    public boolean allowQuery(Long tokenId) {
        return allow(tokenId, ConfigKeys.OPENAPI_QUERY_RATE_PER_MIN);
    }

    private boolean allow(Long tokenId, ConfigKeys key) {
        int limit = config.getInt(key);
        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(tokenId + ":" + key.getKey(), k -> new ArrayDeque<>());
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
