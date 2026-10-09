package com.drawbridge.config;

import com.drawbridge.admin.AdminAuthInterceptor;
import com.drawbridge.openapi.OpenApiAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 装配：/v1/** 业务方鉴权、/admin/** 管理端鉴权（登录放行）；
 * 管理端前端（Vue 构建产物 web/dist）同源托管，免 CORS。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final OpenApiAuthInterceptor openApiAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(openApiAuthInterceptor).addPathPatterns("/v1/**");
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/login");
    }

    /** 业务接口放开 CORS：支持浏览器端工具（Apifox 网页版等）直连调试；管理端不放开。 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST")
                .allowedHeaders("Authorization", "Content-Type", "X-Idempotency-Key")
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 兼容两种启动目录：backend/（IDE/ mvn spring-boot:run）与仓库根（java -jar）
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/", "file:../web/dist/", "file:./web/dist/");
    }
}
