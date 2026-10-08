package com.drawbridge.adapter;

import lombok.Getter;

/**
 * 上游调用异常：携带归一化的错误类别，路由链路据此决定重试/处置渠道/终止任务。
 */
@Getter
public class UpstreamCallException extends RuntimeException {

    private final UpstreamErrorKind kind;
    private final Integer httpStatus;

    public UpstreamCallException(UpstreamErrorKind kind, String message, Integer httpStatus) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public static UpstreamCallException network(String message) {
        return new UpstreamCallException(UpstreamErrorKind.NETWORK_TIMEOUT, message, null);
    }
}
