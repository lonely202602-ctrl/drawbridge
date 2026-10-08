package com.drawbridge.health;

import com.drawbridge.domain.mapper.SystemConfigMapper;
import com.drawbridge.infrastructure.config.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 熔断器测试（默认阈值：窗口 60s / 最小样本 20 / 失败率 50% / 熔断 60s / 半开 3 次成功恢复）。
 */
class ChannelHealthTrackerTest {

    private ChannelHealthTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ChannelHealthTracker(new SystemConfigService(mock(SystemConfigMapper.class)));
    }

    private void failTimes(int n) {
        for (int i = 0; i < n; i++) {
            tracker.recordFailure(1L);
        }
    }

    /** 熔断评估发生在失败事件上：先积累 9 次成功，第 11 次失败时样本 20、失败率 55% 触发熔断。 */
    private void tripChannel(long channelId) {
        for (int i = 0; i < 9; i++) {
            tracker.recordSuccess(channelId);
        }
        for (int i = 0; i < 11; i++) {
            tracker.recordFailure(channelId);
        }
    }

    @Test
    void fewFailuresDoNotTrip() {
        failTimes(5);
        assertTrue(tracker.isAvailable(1L));
        assertEquals(ChannelHealthTracker.NORMAL, tracker.healthState(1L));
    }

    @Test
    void highFailureRateTripsBreaker() {
        tripChannel(1L);
        assertFalse(tracker.isAvailable(1L));
        assertEquals(ChannelHealthTracker.OPEN, tracker.healthState(1L));
    }

    @Test
    void healthyChannelDoesNotTrip() {
        for (int i = 0; i < 19; i++) {
            tracker.recordSuccess(1L);
        }
        failTimes(1);            // 失败率 5%
        assertTrue(tracker.isAvailable(1L));
    }

    @Test
    void breakerRecoversThroughHalfOpen() throws Exception {
        tripChannel(1L);
        assertFalse(tracker.isAvailable(1L));
        // 熔断时长 60s 无法等待——直接把 openUntil 拨快来模拟到期（仅测试用反射）
        var field = ChannelHealthTracker.class.getDeclaredField("windows");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var windows = (java.util.concurrent.ConcurrentHashMap<Long, ?>) field.get(tracker);
        Object window = windows.get(1L);
        var openUntil = window.getClass().getDeclaredField("openUntil");
        openUntil.setAccessible(true);
        openUntil.setLong(window, System.currentTimeMillis() - 1);

        // 到期 → 半开放行 → 3 次成功恢复
        assertTrue(tracker.isAvailable(1L));
        assertEquals(ChannelHealthTracker.HALF_OPEN, tracker.healthState(1L));
        tracker.recordSuccess(1L);
        tracker.recordSuccess(1L);
        tracker.recordSuccess(1L);
        assertEquals(ChannelHealthTracker.NORMAL, tracker.healthState(1L));
    }

    @Test
    void halfOpenProbeFailureReopens() throws Exception {
        tripChannel(1L);
        assertFalse(tracker.isAvailable(1L));
        var field = ChannelHealthTracker.class.getDeclaredField("windows");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var windows = (java.util.concurrent.ConcurrentHashMap<Long, ?>) field.get(tracker);
        Object window = windows.get(1L);
        var openUntil = window.getClass().getDeclaredField("openUntil");
        openUntil.setAccessible(true);
        openUntil.setLong(window, System.currentTimeMillis() - 1);

        assertTrue(tracker.isAvailable(1L));   // 半开
        tracker.recordFailure(1L);             // 探测失败 → 重新熔断
        assertFalse(tracker.isAvailable(1L));
        assertEquals(ChannelHealthTracker.OPEN, tracker.healthState(1L));
    }

    @Test
    void channelsAreIsolated() {
        tripChannel(1L);
        assertFalse(tracker.isAvailable(1L));
        assertTrue(tracker.isAvailable(2L));
        assertEquals(ChannelHealthTracker.NORMAL, tracker.healthState(2L));
    }
}
