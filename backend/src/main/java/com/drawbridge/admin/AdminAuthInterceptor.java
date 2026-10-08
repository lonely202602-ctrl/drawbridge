package com.drawbridge.admin;

import com.drawbridge.common.ApiResponse;
import com.drawbridge.common.ErrorCode;
import com.drawbridge.common.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端鉴权：X-Admin-Token 头（登录颁发），/admin/login 放行。
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";

    private final AdminAuthService adminAuthService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String username = adminAuthService.verify(request.getHeader(HEADER));
        if (username == null) {
            response.setStatus(ErrorCode.ADMIN_UNAUTHORIZED.getHttpStatus());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JsonUtil.toJson(ApiResponse.fail(ErrorCode.ADMIN_UNAUTHORIZED, null)));
            return false;
        }
        request.setAttribute("drawbridge.admin", username);
        return true;
    }
}
