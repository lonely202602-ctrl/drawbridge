package com.drawbridge.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理端 SPA 路由回退：非 API 的页面路径统一转发到 index.html（Vue Router history 模式）。
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/dashboard", "/channels", "/groups", "/apps", "/tasks", "/settings"})
    public String forward() {
        return "forward:/index.html";
    }
}
