package com.drawbridge.adapter;

import lombok.Getter;

/**
 * 上游错误类别（网关内部归一化）。
 * retryable=是否换下一候选渠道重试（上游受理成功后一律不再重试，与本分类无关）；
 * channelAction=对该渠道的处置。
 */
@Getter
public enum UpstreamErrorKind {

    /** 连接/读取超时、网络不可达 */
    NETWORK_TIMEOUT(true, ChannelAction.BREAKER_FEED, "UPSTREAM_ALL_FAILED"),
    /** 上游 5xx */
    UPSTREAM_SERVER_ERROR(true, ChannelAction.BREAKER_FEED, "UPSTREAM_ALL_FAILED"),
    /** 上游 429：换渠道重试，本渠道短冷却 */
    RATE_LIMITED(true, ChannelAction.COOLDOWN, "UPSTREAM_ALL_FAILED"),
    /** 上游余额耗尽：换渠道重试，本渠道自动停用并告警 */
    BALANCE_EXHAUSTED(true, ChannelAction.AUTO_DISABLE, "UPSTREAM_ALL_FAILED"),
    /** 上游 Key 失效/被封：换渠道重试，本渠道自动停用并告警 */
    AUTH_FAILED(true, ChannelAction.AUTO_DISABLE, "UPSTREAM_ALL_FAILED"),
    /** Key 有效但无权访问该模型（模型级权限，渠道其它模型正常）：换渠道重试，不停用渠道 */
    MODEL_NOT_PERMITTED(true, ChannelAction.NONE, "UPSTREAM_MODEL_NOT_PERMITTED"),
    /** 403 拒绝访问（WAF/网关层拦截等，无模型级文案）：只喂熔断换渠道，不停用渠道（防 WAF 误拦关掉健康渠道） */
    ACCESS_FORBIDDEN(true, ChannelAction.BREAKER_FEED, "UPSTREAM_ALL_FAILED"),
    /** 上游查不到该任务（404 任务未找到）：轮询期任务已受理不可重发，直接终态 */
    TASK_NOT_FOUND(false, ChannelAction.NONE, "UPSTREAM_TASK_LOST"),
    /** 渠道当前没有可服务此模型的账号/容量（如 new-api 系 404「无可用渠道」）：换渠道重试，不停用渠道 */
    MODEL_UNAVAILABLE(true, ChannelAction.NONE, "UPSTREAM_MODEL_UNAVAILABLE"),
    /** 提示词/内容审核拦截：不重试，作为任务结果返回业务方 */
    CONTENT_REJECTED(false, ChannelAction.NONE, "CONTENT_REJECTED"),
    /** 参数/模型/尺寸上游不支持：不重试，作为任务结果返回业务方 */
    PARAM_INVALID(false, ChannelAction.NONE, "PARAM_INVALID"),
    /** 异步任务在上游侧失败（轮询期）：不重试（已受理），作为任务结果返回 */
    UPSTREAM_TASK_FAILED(false, ChannelAction.NONE, "UPSTREAM_TASK_FAILED"),
    /** 未识别错误：按可重试处理 */
    UNKNOWN(true, ChannelAction.BREAKER_FEED, "UPSTREAM_ALL_FAILED");

    private final boolean retryable;
    private final ChannelAction channelAction;
    /** 映射到对外任务 error_code */
    private final String externalCode;

    UpstreamErrorKind(boolean retryable, ChannelAction channelAction, String externalCode) {
        this.retryable = retryable;
        this.channelAction = channelAction;
        this.externalCode = externalCode;
    }

    public enum ChannelAction {
        NONE, BREAKER_FEED, COOLDOWN, AUTO_DISABLE
    }
}
