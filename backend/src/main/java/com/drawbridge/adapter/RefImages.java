package com.drawbridge.adapter;

import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 参考图注入的家族通用规则（同步/异步两个适配器共用），由渠道 extra_config.refImageStyle 选择：
 * uuapi → 图生图切 edits 系端点、参考图包成 images[].image_url；
 * image_urls → APIMart 风格，参考图作为 image_urls 数组（URL/base64 混填），无独立 edits 端点；
 * 默认 → 单张直传 image 字符串、多张传 image 数组。
 */
final class RefImages {

    private RefImages() {
    }

    /** 是否走 uuapi edits 端点切换（仅此风格有独立图生图端点）。 */
    static boolean uuapiEdits(ChannelConfigService.ChannelRuntime rt, List<String> imageUrls) {
        return "uuapi".equals(rt.extra().get("refImageStyle"))
                && imageUrls != null && !imageUrls.isEmpty();
    }

    /**
     * 是否走 OpenAI 官方 edits 端点（multipart 文件上传，apiyi/官方 gpt-image 系）。
     * 参考图由网关代下载后作为 image[] 文件字段发送；端点路径由提交方决定（默认 /images/edits）。
     */
    static boolean openAiEdits(ChannelConfigService.ChannelRuntime rt, List<String> imageUrls) {
        return "openai_edits".equals(rt.extra().get("refImageStyle"))
                && imageUrls != null && !imageUrls.isEmpty();
    }

    static void applyTo(ObjectNode body, List<String> imageUrls, ChannelConfigService.ChannelRuntime rt) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return;
        }
        String style = rt.extra().get("refImageStyle") instanceof String s ? s : "";
        if ("openai_edits".equals(style)) {
            // 参考图走 multipart 文件上传（存档以 _mode/_refImages 标注），JSON 体不注入图片字段
            return;
        }
        if ("uuapi".equals(style)) {
            ArrayNode images = body.putArray("images");
            for (String u : imageUrls) {
                images.addObject().put("image_url", u);
            }
            return;
        }
        if ("image_urls".equals(style)) {
            ArrayNode arr = body.putArray("image_urls");
            imageUrls.forEach(arr::add);
            return;
        }
        if (imageUrls.size() == 1) {
            body.put("image", imageUrls.getFirst());
        } else {
            ArrayNode arr = body.putArray("image");
            imageUrls.forEach(arr::add);
        }
    }
}
