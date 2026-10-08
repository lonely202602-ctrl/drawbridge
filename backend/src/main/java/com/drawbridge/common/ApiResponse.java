package com.drawbridge.common;

/**
 * 统一响应壳：{code, msg, data}。code=0 成功；非 0 见 {@link ErrorCode}。
 */
public record ApiResponse<T>(int code, String msg, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode code, String msg) {
        return new ApiResponse<>(code.getCode(), msg == null ? code.getDefaultMsg() : msg, null);
    }
}
