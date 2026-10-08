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
                {"model":"image2.5","prompt":"一只猫","n":2,"size":"1024x1024",
                 "aspectRatio":"3:2","image":"https://a.com/1.png",
                 "images":["https://b.com/2.png"],
                 "options":{"output_format":"webp","quality":"high"}}
                """);
        assertEquals("一只猫", p.prompt());
        assertEquals(2, p.n());
        assertEquals("1024x1024", p.size());
        assertEquals("3:2", p.aspectRatio());
        assertEquals(2, p.images().size());
        assertEquals("high", p.options().get("quality"));
    }

    @Test
    void parseMinimalPayload() {
        SubmitPayload p = SubmitPayload.parse("{\"prompt\":\"hi\"}");
        assertEquals("hi", p.prompt());
        assertEquals(1, p.n());
        assertNull(p.size());
        assertTrue(p.images().isEmpty());
        assertTrue(p.options().isEmpty());
    }

    @Test
    void parseGarbageTolerated() {
        SubmitPayload p = SubmitPayload.parse("not-json");
        assertNull(p.prompt());
        assertEquals(1, p.n());
    }
}
