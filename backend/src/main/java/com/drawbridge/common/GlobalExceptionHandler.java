package com.drawbridge.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常按错误码转响应；其余一律 500 且不泄漏内部细节。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> biz(BizException e) {
        return ResponseEntity.status(e.getCode().getHttpStatus())
                .body(ApiResponse.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().isEmpty() ? "请求参数不合法"
                : e.getBindingResult().getFieldErrors().getFirst().getDefaultMessage();
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, detail));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, "请求体缺失或不是合法 JSON"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unknown(Exception e) {
        log.error("[drawbridge] 未处理异常", e);
        return ResponseEntity.status(500).body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "网关内部错误"));
    }
}
