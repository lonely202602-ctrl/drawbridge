package com.drawbridge.adapter;

import lombok.Getter;

/**
 * 上游调用异常：携带归一化的错误类别，路由链路据此决定重试/处置渠道/终止任务。
 * accountScope：仅余额耗尽使用——精确余额信号（BALANCE_EXPLICIT 正则命中）时为 true，
 * 触发同账号（同 base_url+apiKey）渠道批量停用；宽匹配维持单渠道停用，防误判放大。
 */
@Getter
public class UpstreamCallException extends RuntimeException {

    private final UpstreamErrorKind kind;
    private final Integer httpStatus;
    private final boolean accountScope;

    public UpstreamCallException(UpstreamErrorKind kind, String message, Integer httpStatus) {
        this(kind, message, httpStatus, false);
    }

    public UpstreamCallException(UpstreamErrorKind kind, String message, Integer httpStatus, boolean accountScope) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.accountScope = accountScope;
    }

    public static UpstreamCallException network(String message) {
        return new UpstreamCallException(UpstreamErrorKind.NETWORK_TIMEOUT, message, null);
    }
}
