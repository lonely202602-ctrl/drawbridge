package com.drawbridge.adapter;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分辨率契约适配：业务方的「比例（W:H）+ 档位（1k/2k/4k）」翻译成各协议族上游的参数。
 *
 * OpenAI 系（gpt-image 家族，size=宽x高像素串）：
 * - 旧版 size（deprecated）原样透传——老调用方传 1792x1024 这类模型精确尺寸时不做有损换算；
 * - 渠道 extra_config.sizeMap：字符串=该档位固定尺寸（整表覆盖，横竖一律此值）；
 *   对象=按方向配（{"2k":{"landscape":"2048x1152","portrait":"1152x2048","square":"2048x2048"}}）；
 * - 业务方显式传了 aspectRatio → 查「比例×档位」30 档像素表（apiyi 官方对照，见 SIZE_30）；
 *   枚举外比例按对数距离就近吸附（7:4→16:9 档），档位缺省按 1k；
 * - 没传比例 → 返回 null（不带 size，模型用自己的默认：修图保持参考图方向，不会裁成方图）。
 *
 * Crun 系（原生认比例+档位）：显式传了才发；旧版 size 兜底换算成最简比+长边定档。
 */
final class Resolutions {

    private static final Pattern LEGACY_SIZE = Pattern.compile("(\\d{3,4})[xX](\\d{3,4})");

    private Resolutions() {
    }

    /**
     * gpt-image 家族「比例×档位」→像素 size 官方 30 档对照表（apiyi gpt-image-2-vip 概览页）。
     * 30 个值全部满足官转自定义尺寸约束（两边 16 的倍数、长短比 ≤3:1、0.65–8.3MP、最大边 ≤3840），
     * 官转/vip 通用；-all 系由 omitSize 整体跳过 size。
     */
    private static final Map<String, Map<String, String>> SIZE_30 = Map.of(
            "1k", Map.of("1:1", "1280x1280", "2:3", "848x1280", "3:2", "1280x848",
                    "3:4", "960x1280", "4:3", "1280x960", "4:5", "1024x1280",
                    "5:4", "1280x1024", "9:16", "720x1280", "16:9", "1280x720", "21:9", "1280x544"),
            "2k", Map.of("1:1", "2048x2048", "2:3", "1360x2048", "3:2", "2048x1360",
                    "3:4", "1536x2048", "4:3", "2048x1536", "4:5", "1632x2048",
                    "5:4", "2048x1632", "9:16", "1152x2048", "16:9", "2048x1152", "21:9", "2048x864"),
            "4k", Map.of("1:1", "2880x2880", "2:3", "2336x3520", "3:2", "3520x2336",
                    "3:4", "2480x3312", "4:3", "3312x2480", "4:5", "2560x3216",
                    "5:4", "3216x2560", "9:16", "2160x3840", "16:9", "3840x2160", "21:9", "3840x1632"));

    /** OpenAI 系尺寸决策；返回 null 表示本次请求不带 size。 */
    static String openAiSize(String aspectRatio, String resolution, String legacySize, Map<String, Object> extra) {
        // 渠道级 omitSize：官逆 gpt-image-2-all/-all 类模型不接受 size 字段（画幅写 prompt），发了反而报错
        if (truthy(extra.get("omitSize"))) {
            return null;
        }
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
        // 比例就近吸附常用枚举（枚举外如 7:4 → 16:9 档），档位归一（缺省/未知按 1k 最保守）
        String ratioText = nearestCommonRatio(parseRatio(aspectRatio));
        String tier = switch (resolution == null ? "" : resolution.trim().toLowerCase()) {
            case "2k" -> "2k";
            case "4k" -> "4k";
            default -> "1k";
        };
        return SIZE_30.get(tier).get(ratioText);
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

    /** extra_config 布尔键取值：JSON 解析后的 Boolean 或字符串 "true" 都认。 */
    static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

}
