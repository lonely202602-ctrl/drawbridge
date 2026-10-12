package com.drawbridge.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理端 SPA 路由回退：非 API 的页面路径统一转发到 index.html（Vue Router history 模式）。
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/dashboard", "/channels", "/groups", "/apps", "/tasks", "/queue-monitor",
            "/settings", "/docs"})
    public String forward(jakarta.servlet.http.HttpServletResponse response) {
        // index.html 禁缓存：前端重新构建后，浏览器刷新即拿到最新哈希资源清单
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        return "forward:/index.html";
    }
}
