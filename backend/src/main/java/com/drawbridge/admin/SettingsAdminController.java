package com.drawbridge.admin;

import com.drawbridge.common.ApiResponse;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理端-设置：运营参数（重试/熔断/轮询/保留/限流）热更新。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/settings")
public class SettingsAdminController {

    private final SystemConfigService config;

    /** values=当前值；descriptions=键的中文描述（设置页标签展示，未知键回退键名）。 */
    @GetMapping
    public ApiResponse<Map<String, Map<String, String>>> get() {
        Map<String, String> values = config.all();
        Map<String, String> descriptions = new HashMap<>();
        for (String key : values.keySet()) {
            descriptions.put(key, ConfigKeys.descriptionOf(key));
        }
        return ApiResponse.ok(Map.of("values", values, "descriptions", descriptions));
    }

    @PutMapping
    public ApiResponse<Void> put(@RequestBody Map<String, String> values) {
        config.updateAll(values);
        return ApiResponse.ok();
    }
}
