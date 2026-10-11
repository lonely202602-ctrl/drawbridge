package com.drawbridge.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提交载荷解析测试。
 */
class SubmitPayloadTest {

    @Test
    void parseFullPayload() {
        SubmitPayload p = SubmitPayload.parse("""
                {"model":"image2.5","prompt":"一只猫","n":1,"resolution":"2k",
                 "aspectRatio":"3:2",
                 "images":["https://a.com/1.png","https://b.com/2.png"],
                 "options":{"output_format":"webp","quality":"high"}}
                """);
        assertEquals("一只猫", p.prompt());
        assertEquals(1, p.n());
        assertEquals("2k", p.resolution());
        assertEquals("3:2", p.aspectRatio());
        assertEquals(2, p.images().size());
        assertEquals("high", p.options().get("quality"));
    }

    @Test
    void parseMinimalPayload() {
        SubmitPayload p = SubmitPayload.parse("{\"prompt\":\"hi\"}");
        assertEquals("hi", p.prompt());
        assertEquals(1, p.n());
        assertNull(p.resolution());
        assertTrue(p.images().isEmpty());
        assertTrue(p.options().isEmpty());
    }

    @Test
    void parseLegacySizeAndNullContractFields() {
        // 旧版 size 兼容读取（发版在途任务/deprecated 入参）；没传比例档位就是 null，不脑补默认
        SubmitPayload p = SubmitPayload.parse(
                "{\"prompt\":\"x\",\"size\":\"1792x1024\"}");
        assertEquals("1792x1024", p.legacySize());
        assertNull(p.resolution());
        assertNull(p.aspectRatio());
        assertTrue(p.images().isEmpty());
    }

    @Test
    void parseGarbageTolerated() {
        SubmitPayload p = SubmitPayload.parse("not-json");
        assertNull(p.prompt());
        assertEquals(1, p.n());
    }
}
