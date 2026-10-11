package com.drawbridge.adapter;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分辨率契约适配：业务方的「比例（W:H）+ 档位（1k/2k/4k）」翻译成各协议族上游的参数。
 *
 * OpenAI 系（没有比例参数只有宽x高）：
 * - 旧版 size（deprecated）原样透传——老调用方传 1792x1024 这类模型精确尺寸时不做有损换算；
 * - 渠道 extra_config.sizeMap：字符串=该档位固定尺寸（整表覆盖，横竖一律此值）；
 *   对象=按方向配（{"2k":{"landscape":"2048x1152","portrait":"1152x2048","square":"2048x2048"}}）；
 * - 业务方显式传了 aspectRatio → 方向归档三档（1536x1024 / 1024x1536 / 1024x1024）；
 * - 没传比例 → 返回 null（不带 size，模型用自己的默认：修图保持参考图方向，不会裁成方图）。
 * 档位信息在这族只有约 1k 一档，2k/4k 静默降级为能给的最好档（业务方不感知上游）。
 *
 * Crun 系（原生认比例+档位）：显式传了才发；旧版 size 兜底换算成最简比+长边定档。
 */
final class Resolutions {

    private static final Pattern LEGACY_SIZE = Pattern.compile("(\\d{3,4})[xX](\\d{3,4})");

    private Resolutions() {
    }

    /** OpenAI 系尺寸决策；返回 null 表示本次请求不带 size。 */
    static String openAiSize(String aspectRatio, String resolution, String legacySize, Map<String, Object> extra) {
        if (legacySize != null && !legacySize.isBlank() && !"auto".equalsIgnoreCase(legacySize)) {
            return legacySize;
        }
        if (extra.get("sizeMap") instanceof Map<?, ?> map) {
            Object v = map.get(String.valueOf(resolution).toLowerCase());
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
            if (v instanceof Map<?, ?> byDirection) {
                double ratio = parseRatio(aspectRatio);
                String key = ratio > 1.05 ? "landscape" : ratio < 0.95 ? "portrait" : "square";
                if (byDirection.get(key) instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        if (aspectRatio == null || aspectRatio.isBlank()) {
            return null;
        }
        double ratio = parseRatio(aspectRatio);
        if (ratio > 1.05) {
            return "1536x1024";
        }
        return ratio < 0.95 ? "1024x1536" : "1024x1024";
    }

    /**
     * 主流生图上游普遍接受的比例枚举（以 Crun 官方参数表为准，行业通用）。
     * 旧版 size 换算出的最简比不在集合内时按对数距离就近吸附——
     * 实测 Crun gpt-image-2-5 对枚举外的比例（如 7:4）直接 422，宁可近似也不给业务方一个必败请求。
     */
    private static final double[] COMMON_RATIOS = {
            1.0 / 1, 3.0 / 2, 2.0 / 3, 4.0 / 3, 3.0 / 4, 5.0 / 4, 4.0 / 5, 16.0 / 9, 9.0 / 16, 21.0 / 9
    };
    private static final String[] COMMON_RATIO_TEXTS = {
            "1:1", "3:2", "2:3", "4:3", "3:4", "5:4", "4:5", "16:9", "9:16", "21:9"
    };

    /**
     * Crun 系：业务方没传比例/档位但传了旧版 size 时的换算（1792x1024 → 16:9 + 2k）。
     * 档位按长边定（≤1024→1k、≤2048→2k、否则 4k）。返回 [aspectRatio, resolution]；size 非法或为空返回 null。
     */
    static String[] crunSizeFromLegacy(String legacySize) {
        if (legacySize == null || legacySize.isBlank()) {
            return null;
        }
        Matcher m = LEGACY_SIZE.matcher(legacySize.trim());
        if (!m.matches()) {
            return null;
        }
        int w = Integer.parseInt(m.group(1));
        int h = Integer.parseInt(m.group(2));
        String ratio = nearestCommonRatio((double) w / h);
        long maxSide = Math.max(w, h);
        String tier = maxSide <= 1024 ? "1k" : maxSide <= 2048 ? "2k" : "4k";
        return new String[]{ratio, tier};
    }

    /** 按对数距离取最近常用比例（对数轴上 16:9↔21:9 的"距离感"与 4:3↔3:2 一致）。 */
    private static String nearestCommonRatio(double ratio) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < COMMON_RATIOS.length; i++) {
            double dist = Math.abs(Math.log(ratio) - Math.log(COMMON_RATIOS[i]));
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return COMMON_RATIO_TEXTS[best];
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
