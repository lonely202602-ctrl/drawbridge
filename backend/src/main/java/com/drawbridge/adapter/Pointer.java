package com.drawbridge.adapter;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 JSON 取值路径：支持 a.b.c、data[0].url、data[*].url。
 * 异步协议族的响应字段位置可由渠道 extra_config 覆盖（声明式适配的基础）。
 */
public final class Pointer {

    private Pointer() {
    }

    /** 返回路径命中的所有节点（[*] 展开数组）。 */
    public static List<JsonNode> resolveAll(JsonNode root, String path) {
        List<JsonNode> current = List.of(root);
        for (String seg : path.split("\\.")) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                String field = seg;
                int bracket = seg.indexOf('[');
                if (bracket >= 0) {
                    field = seg.substring(0, bracket);
                }
                if (!field.isBlank()) {
                    node = node.path(field);
                }
                if (bracket >= 0) {
                    String indexPart = seg.substring(bracket);
                    if ("[*]".equals(indexPart)) {
                        if (node.isArray()) {
                            node.forEach(next::add);
                        }
                        continue;
                    }
                    String idx = indexPart.replaceAll("[\\[\\]]", "");
                    node = node.path(Integer.parseInt(idx));
                }
                next.add(node);
            }
            current = next;
        }
        return current;
    }

    /** 返回路径命中的第一个文本值，取不到返回 null。 */
    public static String text(JsonNode root, String path) {
        for (JsonNode node : resolveAll(root, path)) {
            if (node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
            if (node.isValueNode() && !node.isNull()) {
                return node.asText();
            }
        }
        return null;
    }

    /** 拼接 base 与 path：base 末尾斜杠与 path 开头斜杠互斥。 */
    public static String joinUrl(String base, String path) {
        String b = base == null ? "" : base.replaceAll("/+$", "");
        String p = path == null ? "" : path.startsWith("/") ? path : "/" + path;
        return b + p;
    }
}
