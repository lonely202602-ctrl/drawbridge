package com.drawbridge.common;

import lombok.Getter;

/**
 * 业务异常：携带对外错误码与可展示消息，由全局异常处理器统一转响应。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode code;

    public BizException(ErrorCode code) {
        this(code, code.getDefaultMsg());
    }

    public BizException(ErrorCode code, String msg) {
        super(msg);
        this.code = code;
    }
}
