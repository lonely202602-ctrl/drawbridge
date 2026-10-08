package com.drawbridge.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 工具测试（重点是存档截断的字节安全性）。
 */
class JsonUtilTest {

    @Test
    void truncateBytesRespectsUtf8Boundary() {
        String s = "画桥drawbridge网关测试".repeat(100);
        String out = JsonUtil.truncateBytes(s, 100);
        // 截断结果（不含尾缀）必须是合法 UTF-8，且字节数不超过上限+尾缀
        assertTrue(out.getBytes(StandardCharsets.UTF_8).length <= 100 + "...[truncated]".getBytes(StandardCharsets.UTF_8).length);
        assertTrue(out.endsWith("...[truncated]"));
    }

    @Test
    void truncateShortStringNoop() {
        assertEquals("abc", JsonUtil.truncateBytes("abc", 100));
        assertEquals(null, JsonUtil.truncateBytes(null, 100));
    }

    @Test
    void toJsonRoundtrip() {
        Map<String, Object> m = new HashMap<>();
        m.put("a", 1);
        m.put("b", "文本");
        String json = JsonUtil.toJson(m);
        assertTrue(json.contains("\"b\":\"文本\"") || json.contains("\"b\": \"文本\""));
    }
}
