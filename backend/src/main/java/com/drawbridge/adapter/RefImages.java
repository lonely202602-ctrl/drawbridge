package com.drawbridge.adapter;

import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 参考图注入的家族通用规则（同步/异步两个适配器共用）：
 * 渠道 extra_config.refImageStyle=uuapi 且本次带参考图 → 图生图切 edits 系端点、
 * 参考图包成 images[].image_url；默认风格单张直传 image 字符串、多张传 image 数组。
 */
final class RefImages {

    private RefImages() {
    }

    static boolean uuapiEdits(ChannelConfigService.ChannelRuntime rt, List<String> imageUrls) {
        return "uuapi".equals(rt.extra().get("refImageStyle"))
                && imageUrls != null && !imageUrls.isEmpty();
    }

    static void applyTo(ObjectNode body, List<String> imageUrls, boolean uuapiEdits) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return;
        }
        if (uuapiEdits) {
            ArrayNode images = body.putArray("images");
            for (String u : imageUrls) {
                images.addObject().put("image_url", u);
            }
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
