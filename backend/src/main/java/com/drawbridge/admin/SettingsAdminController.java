package com.drawbridge.admin;

import com.drawbridge.common.ApiResponse;
import com.drawbridge.infrastructure.config.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端-设置：运营参数（重试/熔断/轮询/保留/限流）热更新。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/settings")
public class SettingsAdminController {

    private final SystemConfigService config;

    @GetMapping
    public ApiResponse<Map<String, String>> get() {
        return ApiResponse.ok(config.all());
    }

    @PutMapping
    public ApiResponse<Void> put(@RequestBody Map<String, String> values) {
        config.updateAll(values);
        return ApiResponse.ok();
    }
}
