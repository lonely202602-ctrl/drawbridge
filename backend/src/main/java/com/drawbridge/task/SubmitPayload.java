package com.drawbridge.task;

import com.drawbridge.common.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务方提交载荷（biz_request 存档的结构化视图）。
 */
public record SubmitPayload(
        String prompt,
        int n,
        String size,
        String aspectRatio,
        List<String> images,
        Map<String, Object> options) {

    public static SubmitPayload parse(String json) {
        JsonNode node = JsonUtil.readTree(json);
        List<String> images = new ArrayList<>();
        if (node.hasNonNull("image") && node.path("image").isTextual()) {
            images.add(node.get("image").asText());
        }
        JsonNode imgs = node.path("images");
        if (imgs.isArray()) {
            imgs.forEach(i -> {
                if (i.isTextual() && !i.asText().isBlank()) {
                    images.add(i.asText());
                }
            });
        }
        Map<String, Object> options = new LinkedHashMap<>();
        JsonNode opts = node.path("options");
        if (opts.isObject()) {
            opts.fields().forEachRemaining(e -> options.put(e.getKey(),
                    JsonUtil.MAPPER.convertValue(e.getValue(), Object.class)));
        }
        return new SubmitPayload(
                node.path("prompt").isTextual() ? node.get("prompt").asText() : null,
                Math.max(1, node.path("n").asInt(1)),
                node.path("size").isTextual() ? node.get("size").asText() : null,
                node.path("aspectRatio").isTextual() ? node.get("aspectRatio").asText() : null,
                List.copyOf(images),
                options);
    }
}
