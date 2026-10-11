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
     * 防御性钳制：非正值回退默认 32KB，上限不超过 TEXT 列容量——存档配置错误不应引发写库 500。
     */
    public static String truncateBytes(String s, int maxBytes) {
        int cap = maxBytes <= 0 ? 32_768 : Math.min(maxBytes, 65_000);
        if (s == null) {
            return s;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= cap) {
            return s;
        }
        int end = Math.min(s.length(), cap);
        while (end > 0 && s.substring(0, end).getBytes(StandardCharsets.UTF_8).length > cap) {
            end--;
        }
        return s.substring(0, end) + "...[truncated]";
    }
}
