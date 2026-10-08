package com.drawbridge.health;

import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道冷却：上游 429 后短时回避（会自愈，区别于熔断/停用）。
 */
@Component
public class CooldownRegistry {

    private final SystemConfigService config;
    private final Map<Long, Long> untilByChannel = new ConcurrentHashMap<>();

    public CooldownRegistry(SystemConfigService config) {
        this.config = config;
    }

    public void cool(Long channelId) {
        untilByChannel.put(channelId, System.currentTimeMillis() + config.getLong(ConfigKeys.RATE_LIMIT_COOLDOWN_MS));
    }

    public boolean isCooling(Long channelId) {
        Long until = untilByChannel.get(channelId);
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            untilByChannel.remove(channelId);
            return false;
        }
        return true;
    }
}
