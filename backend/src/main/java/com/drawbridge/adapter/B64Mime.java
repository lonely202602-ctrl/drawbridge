package com.drawbridge.adapter;

/**
 * base64 魔数嗅探图片格式：响应里没有 output_format 字段时按图片头判型，
 * 不再盲拼 image/png（jpeg/webp 数据挂 png 的 MIME 会影响部分业务方的解析）。
 */
final class B64Mime {

    private B64Mime() {
    }

    /** 返回 MIME 子类型（png/jpeg/webp/gif）；认不出按 png 兜底（生图上游最常见）。 */
    static String sniff(String b64) {
        if (b64 == null || b64.length() < 8) {
            return "png";
        }
        if (b64.startsWith("iVBOR")) {
            return "png";       // 89 50 4E 47 (\x89PNG)
        }
        if (b64.startsWith("/9j/")) {
            return "jpeg";      // FF D8 FF
        }
        if (b64.startsWith("UklGR")) {
            return "webp";      // RIFF....WEBP
        }
        if (b64.startsWith("R0lGOD")) {
            return "gif";       // GIF8
        }
        return "png";
    }
}
