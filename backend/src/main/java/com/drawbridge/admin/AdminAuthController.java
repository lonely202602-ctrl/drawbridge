package com.drawbridge.admin;

import com.drawbridge.common.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端登录。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@Validated @RequestBody LoginRequest body) {
        return ApiResponse.ok(Map.of("token", adminAuthService.login(body.username(), body.password())));
    }
}
