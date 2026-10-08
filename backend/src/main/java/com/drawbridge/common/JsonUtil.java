package com.drawbridge.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;

/**
 * JSON 工具：全局共享一个 ObjectMapper（注册 JDK 时间模块、忽略 null 字段）。
 */
public final class JsonUtil {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonUtil() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 序列化失败", e);
        }
    }

    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json == null ? "" : json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    public static <T> T readValue(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json == null ? "{}" : json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 反序列化失败", e);
        }
    }

    /**
     * 按字节数截断字符串（存档上限用），保证不破坏 UTF-8 字符。
     */
    public static String truncateBytes(String s, int maxBytes) {
        if (s == null || maxBytes <= 0) {
            return s;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        int end = Math.min(s.length(), maxBytes);
        while (end > 0 && s.substring(0, end).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            end--;
        }
        return s.substring(0, end) + "...[truncated]";
    }
}
