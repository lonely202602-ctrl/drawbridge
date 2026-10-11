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
    void halfOpenIdleExpiresSoRouteStopsBottomRanking() throws Exception {
        tripChannel(1L);
        assertFalse(tracker.isAvailable(1L));
        promoteToHalfOpen(1L);
        assertTrue(tracker.isAvailable(1L));
        assertEquals(ChannelHealthTracker.HALF_OPEN, tracker.healthState(1L));
        // 刚进半开：还没停滞（默认阈值 120s），路由应继续垫底
        assertFalse(tracker.halfOpenExpired(1L));
        // 拨快 halfOpenSince 两分钟前 → 停滞，路由放它回正常排队位置
        var field = ChannelHealthTracker.class.getDeclaredField("windows");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var windows = (java.util.concurrent.ConcurrentHashMap<Long, ?>) field.get(tracker);
        Object window = windows.get(1L);
        var since = window.getClass().getDeclaredField("halfOpenSince");
        since.setAccessible(true);
        since.setLong(window, System.currentTimeMillis() - 121_000);
        assertTrue(tracker.halfOpenExpired(1L), "停滞超阈值的半开应被豁免垫底");
    }

    /** 把 openUntil 拨到过去并触发一次 isAvailable，使渠道进入半开（熔断时长 60s 无法真实等待）。 */
    private void promoteToHalfOpen(long channelId) throws Exception {
        var field = ChannelHealthTracker.class.getDeclaredField("windows");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var windows = (java.util.concurrent.ConcurrentHashMap<Long, ?>) field.get(tracker);
        Object window = windows.get(channelId);
        var openUntil = window.getClass().getDeclaredField("openUntil");
        openUntil.setAccessible(true);
        openUntil.setLong(window, System.currentTimeMillis() - 1);
        assertTrue(tracker.isAvailable(channelId));
    }

    @Test
    void channelsAreIsolated() {
        tripChannel(1L);
        assertFalse(tracker.isAvailable(1L));
        assertTrue(tracker.isAvailable(2L));
        assertEquals(ChannelHealthTracker.NORMAL, tracker.healthState(2L));
    }
}
