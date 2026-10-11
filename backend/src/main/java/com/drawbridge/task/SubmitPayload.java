package com.drawbridge.task;

import com.drawbridge.common.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务方提交载荷（biz_request 存档的结构化视图）。
 * resolution/aspectRatio/size 允许为 null（业务方没传就是没传，适配器不得脑补默认值）；
 * size 为旧版字段，读进来只为兼容发版在途任务与 deprecated 入参。
 */
public record SubmitPayload(
        String prompt,
        int n,
        String resolution,
        String aspectRatio,
        String legacySize,
        List<String> images,
        Map<String, Object> options) {

    public static SubmitPayload parse(String json) {
        JsonNode node = JsonUtil.readTree(json);
        List<String> images = new ArrayList<>();
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
                node.path("resolution").isTextual() ? node.get("resolution").asText() : null,
                node.path("aspectRatio").isTextual() ? node.get("aspectRatio").asText() : null,
                node.path("size").isTextual() ? node.get("size").asText() : null,
                List.copyOf(images),
                options);
    }
}
