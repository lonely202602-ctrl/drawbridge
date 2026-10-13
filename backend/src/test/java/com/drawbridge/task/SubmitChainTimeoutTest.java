package com.drawbridge.task;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 渠道级提交超时覆盖：apiyi 同步生图高画质 3-5 分钟，全局 120s 不够，extra_config.timeoutMs 渠道级覆盖。 */
class SubmitChainTimeoutTest {

    @Test
    void channelTimeoutMsOverridesGlobal() {
        assertEquals(300_000, SubmitChain.effectiveSubmitTimeout(120_000, Map.of("timeoutMs", 300_000)));
        // 字符串数字也认（防手配 JSON 写成字符串）
        assertEquals(600_000, SubmitChain.effectiveSubmitTimeout(120_000, Map.of("timeoutMs", "600000")));
    }

    @Test
    void fallbackToGlobalWhenAbsentOrInvalid() {
        assertEquals(120_000, SubmitChain.effectiveSubmitTimeout(120_000, null));
        assertEquals(120_000, SubmitChain.effectiveSubmitTimeout(120_000, Map.of()));
        assertEquals(120_000, SubmitChain.effectiveSubmitTimeout(120_000, Map.of("timeoutMs", 0)));
        assertEquals(120_000, SubmitChain.effectiveSubmitTimeout(120_000, Map.of("timeoutMs", -5)));
        assertEquals(120_000, SubmitChain.effectiveSubmitTimeout(120_000, Map.of("timeoutMs", "abc")));
    }

    @Test
    void clampedToOneSecondAndTenMinutes() {
        assertEquals(1_000, SubmitChain.effectiveSubmitTimeout(120_000, Map.of("timeoutMs", 1)));
        assertEquals(600_000, SubmitChain.effectiveSubmitTimeout(120_000, Map.of("timeoutMs", 3_600_000)));
    }
}
