package com.drawbridge.common;

import lombok.Getter;

/**
 * 对外 API 错误码。code 同时决定 HTTP 状态。
 */
@Getter
public enum ErrorCode {
    OK(0, "ok", 200),
    PARAM_INVALID(40001, "请求参数不合法", 400),
    UNAUTHORIZED(40101, "未授权或令牌无效", 401),
    FORBIDDEN(40301, "无权访问该模型", 403),
    NOT_FOUND(40404, "资源不存在", 404),
    QUOTA_EXCEEDED(40201, "令牌额度不足", 402),
    RATE_LIMITED(42901, "请求过于频繁，请稍后重试", 429),
    TOO_MANY_QUEUED(42902, "该令牌排队任务已达上限，请等待在途任务完成后再提交", 429),
    NO_AVAILABLE_CHANNEL(50301, "该模型暂无可用上游渠道", 503),
    INTERNAL_ERROR(50000, "网关内部错误", 500),
    ADMIN_UNAUTHORIZED(40102, "管理端未登录或登录已过期", 401);

    private final int code;
    private final String defaultMsg;
    private final int httpStatus;

    ErrorCode(int code, String defaultMsg, int httpStatus) {
        this.code = code;
        this.defaultMsg = defaultMsg;
        this.httpStatus = httpStatus;
    }
}
