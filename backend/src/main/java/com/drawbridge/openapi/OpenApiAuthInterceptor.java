package com.drawbridge.openapi;

import com.drawbridge.common.ApiResponse;
import com.drawbridge.common.ErrorCode;
import com.drawbridge.common.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 业务方 API 鉴权：Authorization: Bearer sk-xxx → AuthContext 挂到请求属性。
 */
@Component
@RequiredArgsConstructor
public class OpenApiAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_AUTH = "drawbridge.auth";

    private final TokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return reject(response, ErrorCode.UNAUTHORIZED, "缺少 Authorization: Bearer sk-xxx");
        }
        String sk = authorization.substring(7).trim();
        TokenService.AuthContext ctx = tokenService.resolve(sk);
        if (ctx == null) {
            return reject(response, ErrorCode.UNAUTHORIZED, "令牌无效、已停用或已过期");
        }
        request.setAttribute(ATTR_AUTH, ctx);
        return true;
    }

    private boolean reject(HttpServletResponse response, ErrorCode code, String msg) throws Exception {
        response.setStatus(code.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonUtil.toJson(ApiResponse.fail(code, msg)));
        return false;
    }
}
