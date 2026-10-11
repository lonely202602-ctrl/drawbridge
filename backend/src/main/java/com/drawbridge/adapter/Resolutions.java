package com.drawbridge.adapter;

import java.util.Map;

/**
 * 分辨率契约适配（OpenAI 系专用）：业务方的「比例 + 1k/2k/4k 档位」→ 方向归档成该族上游必然接受的三档尺寸。
 * OpenAI 生图协议没有比例参数只有宽x高，档位信息在这族只有约 1k 一档——2k/4k 静默降级为能给的最好档
 * （业务方不感知上游，网关不许把"上游不支持高档"甩回给业务方）；特殊模型用渠道 extra_config.sizeMap 整表覆盖。
 */
final class Resolutions {

    private Resolutions() {
    }

    /** 渠道 sizeMap（{"2k":"2048x2048"}）优先；否则方向归档：方图 1024x1024 / 横版 1536x1024 / 竖版 1024x1536。 */
    static String openAiSize(String aspectRatio, String resolution, Map<String, Object> extra) {
        if (extra.get("sizeMap") instanceof Map<?, ?> map
                && map.get(String.valueOf(resolution).toLowerCase()) instanceof String s && !s.isBlank()) {
            return s;
        }
        double ratio = parseRatio(aspectRatio);
        if (ratio > 1.05) {
            return "1536x1024";
        }
        return ratio < 0.95 ? "1024x1536" : "1024x1024";
    }

    /** 非法/空比例按方图处理（控制器已校验格式，这里是防御）。 */
    static double parseRatio(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()) {
            return 1.0;
        }
        try {
            String[] parts = aspectRatio.split(":");
            double w = Double.parseDouble(parts[0].trim());
            double h = Double.parseDouble(parts[1].trim());
            return w <= 0 || h <= 0 ? 1.0 : w / h;
        } catch (Exception e) {
            return 1.0;
        }
    }
}
