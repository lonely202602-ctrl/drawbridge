package com.drawbridge.adapter;

import com.drawbridge.common.JsonUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误归类测试：覆盖 5 家上游会出现的错误形态。
 */
class ErrorClassifierTest {

    private UpstreamCallException openAi(int status, String body) {
        return ErrorClassifier.fromOpenAi(status, JsonUtil.readTree(body));
    }

    @Test
    void insufficientQuotaMapsToBalance() {
        // apiyi/laozhang 常见形态：403 + insufficient_quota
        UpstreamCallException e = openAi(403, """
                {"error":{"message":"Your access quota is insufficient","type":"insufficient_quota","code":"insufficient_quota"}}
                """);
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED, e.getKind());

        // 中文文案兜底：余额不足
        e = openAi(403, "{\"error\":{\"message\":\"账户余额不足，请充值\"}}");
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED, e.getKind());

        // code 1113 等数字码 + 文案
        e = openAi(429, "{\"error\":{\"message\":\"无可用渠道 (1113): 余额不足\"}}");
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED, e.getKind());
    }

    @Test
    void contentViolationIsTerminal() {
        UpstreamCallException e = openAi(400, """
                {"error":{"message":"Your request was rejected as a result of our safety system. content_policy_violation","type":"invalid_request_error"}}
                """);
        assertEquals(UpstreamErrorKind.CONTENT_REJECTED, e.getKind());

        e = openAi(400, "{\"error\":{\"message\":\"提示词包含敏感内容，内容审核未通过\"}}");
        assertEquals(UpstreamErrorKind.CONTENT_REJECTED, e.getKind());
    }

    @Test
    void rateLimitAndAuthAndServer() {
        assertEquals(UpstreamErrorKind.RATE_LIMITED,
                openAi(429, "{\"error\":{\"message\":\"too many requests\"}}").getKind());
        assertEquals(UpstreamErrorKind.AUTH_FAILED,
                openAi(401, "{\"error\":{\"message\":\"Invalid API key provided\"}}").getKind());
        assertEquals(UpstreamErrorKind.UPSTREAM_SERVER_ERROR,
                openAi(502, "{\"error\":{\"message\":\"bad gateway\"}}").getKind());
        assertEquals(UpstreamErrorKind.PARAM_INVALID,
                openAi(400, "{\"error\":{\"message\":\"size must be one of supported values\"}}").getKind());
    }

    @Test
    void modelUnavailableIsRetryableNotParamError() {
        // uuapi 真实形态：404 + "not supported by any configured account"（账号池没号）
        UpstreamCallException e = openAi(404, """
                {"error":{"message":"Model \\"gpt-image-2\\" is not supported by any configured account in this group"}}
                """);
        assertEquals(UpstreamErrorKind.MODEL_UNAVAILABLE, e.getKind());
        assertTrue(e.getKind().isRetryable(), "没号是渠道侧容量波动，必须可转移");

        // new-api 中文形态：当前分组下无可用渠道（503/404 都可能）
        assertEquals(UpstreamErrorKind.MODEL_UNAVAILABLE,
                openAi(503, "{\"error\":{\"message\":\"当前分组 default 下对于模型 gpt-image-2 无可用渠道\"}}").getKind());
        assertEquals(UpstreamErrorKind.MODEL_UNAVAILABLE,
                openAi(404, "{\"message\":\"无可用渠道\"}").getKind());

        // 不带文案关键词的普通 404 仍是参数错误（语义：路径/模型名确实不对）
        assertEquals(UpstreamErrorKind.PARAM_INVALID,
                openAi(404, "{\"error\":{\"message\":\"not found\"}}").getKind());
    }

    @Test
    void crunEnvelopeMapping() {
        // HTTP 200 + 业务 code 402 = 余额
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED,
                ErrorClassifier.fromCrunCode(402, "credits not enough").getKind());
        // 文案识别
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED,
                ErrorClassifier.fromCrunCode(500, "账户余额不足").getKind());
        assertEquals(UpstreamErrorKind.AUTH_FAILED,
                ErrorClassifier.fromCrunCode(401, "unauthorized").getKind());
        assertEquals(UpstreamErrorKind.CONTENT_REJECTED,
                ErrorClassifier.fromCrunCode(501, "生成失败：图片涉及违规内容").getKind());
        assertEquals(UpstreamErrorKind.RATE_LIMITED,
                ErrorClassifier.fromCrunCode(429, "rate limit").getKind());
        assertEquals(UpstreamErrorKind.PARAM_INVALID,
                ErrorClassifier.fromCrunCode(400, "invalid aspect_ratio").getKind());
        assertEquals(UpstreamErrorKind.UPSTREAM_SERVER_ERROR,
                ErrorClassifier.fromCrunCode(500, "internal error").getKind());
    }

    @Test
    void statusFirstPreventsTextFalsePositives() {
        // 5xx 文案含 load balancer / billing：状态码优先，不再误判没米停渠道
        assertEquals(UpstreamErrorKind.UPSTREAM_SERVER_ERROR,
                openAi(502, "{\"error\":{\"message\":\"load balancer error\"}}").getKind());
        assertEquals(UpstreamErrorKind.UPSTREAM_SERVER_ERROR,
                openAi(503, "{\"error\":{\"message\":\"billing service unavailable\"}}").getKind());
        // 429 + 限流话术带 quota：按限流（短冷却），不停渠道
        assertEquals(UpstreamErrorKind.RATE_LIMITED,
                openAi(429, "{\"error\":{\"message\":\"rate limit quota exceeded\"}}").getKind());
        // WAF/网关层 403：只喂熔断换渠道，不停渠道
        assertEquals(UpstreamErrorKind.ACCESS_FORBIDDEN,
                openAi(403, "{\"error\":{\"message\":\"Request blocked by WAF\"}}").getKind());
        assertTrue(UpstreamErrorKind.ACCESS_FORBIDDEN.isRetryable());
        // 模型级权限（线上真实样本）：换渠道不停渠道
        assertEquals(UpstreamErrorKind.MODEL_NOT_PERMITTED,
                openAi(403, "{\"error\":{\"message\":\"The API key is not authorized to access this model\"}}").getKind());
        // 真·没米（无歧义措辞）仍能被识别
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED,
                openAi(429, "{\"error\":{\"message\":\"无可用渠道 (1113): 余额不足\"}}").getKind());
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED,
                openAi(403, "{\"error\":{\"message\":\"账户余额不足，请充值\"}}").getKind());
        // new-api 503 无可用渠道：容量波动转移，不喂熔断
        assertEquals(UpstreamErrorKind.MODEL_UNAVAILABLE,
                openAi(503, "{\"error\":{\"message\":\"当前分组 default 下对于模型 gpt-image-2 无可用渠道\"}}").getKind());
    }

    @Test
    void crunOfficialErrorCodes() {
        // 官方「常见错误码」表：401/404/422/429/451/500
        assertEquals(UpstreamErrorKind.AUTH_FAILED, ErrorClassifier.fromCrunCode(401, "未授权 - API 密钥无效或缺失").getKind());
        assertEquals(UpstreamErrorKind.TASK_NOT_FOUND, ErrorClassifier.fromCrunCode(404, "任务未找到").getKind());
        assertTrue(!ErrorClassifier.fromCrunCode(404, "任务未找到").getKind().isRetryable(), "任务丢了不可重发");
        assertEquals(UpstreamErrorKind.PARAM_INVALID, ErrorClassifier.fromCrunCode(422, "参数错误").getKind());
        assertEquals(UpstreamErrorKind.RATE_LIMITED, ErrorClassifier.fromCrunCode(429, "超出请求频率限制").getKind());
        assertEquals(UpstreamErrorKind.CONTENT_REJECTED, ErrorClassifier.fromCrunCode(451, "违反模型内容政策").getKind());
        assertEquals(UpstreamErrorKind.UPSTREAM_SERVER_ERROR, ErrorClassifier.fromCrunCode(500, "服务器内部错误").getKind());
    }

    @Test
    void relayQuotaWordingMapsToBalanceAtNarrowLayer() {
        // 中转平台（new-api 系）典型形态：HTTP 403 + code=insufficient_user_quota → 自动停用渠道
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED,
                openAi(403, "{\"error\":{\"message\":\"用户额度不足\",\"code\":\"insufficient_user_quota\"}}").getKind());
        // 中文文案走 403 窄识别
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED,
                openAi(403, "{\"error\":{\"message\":\"用户额度不足，请充值\"}}").getKind());
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED,
                openAi(403, "{\"error\":{\"message\":\"user quota is not enough\"}}").getKind());
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED,
                openAi(429, "{\"error\":{\"message\":\"该令牌额度已用尽\"}}").getKind());
        // 第一层结构化字段也能认 insufficient_user_quota（不管上游配什么状态码）
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED,
                openAi(400, "{\"error\":{\"message\":\"x\",\"type\":\"insufficient_user_quota\",\"code\":\"insufficient_user_quota\"}}").getKind());
    }

    @Test
    void greedyQuotaPatternsDoNotMisfireOnRateLimitWording() {
        // 贪婪跨句回归：限流话术里夹带 quota/额度词样，不能被判成没米停渠道
        assertEquals(UpstreamErrorKind.RATE_LIMITED,
                openAi(429, "{\"error\":{\"message\":\"insufficient capacity, rate limit quota exceeded\"}}").getKind());
        assertEquals(UpstreamErrorKind.RATE_LIMITED,
                openAi(429, "{\"error\":{\"message\":\"slow down, monthly quota exceeded\"}}").getKind());
    }

    @Test
    void moderationWordingIn403And5xx() {
        // 403 带审核文案 → 终态，不把敏感词打满所有渠道
        assertEquals(UpstreamErrorKind.CONTENT_REJECTED,
                openAi(403, "{\"error\":{\"message\":\"提示词内容审核未通过\"}}").getKind());
        // 5xx 带明确审核判定措辞 → 终态
        assertEquals(UpstreamErrorKind.CONTENT_REJECTED,
                openAi(500, "{\"error\":{\"message\":\"生成失败：违规内容\"}}").getKind());
        // 5xx 带子系统名（服务故障，不是审核拒绝）→ 仍按服务错误转移，不误杀任务
        assertEquals(UpstreamErrorKind.UPSTREAM_SERVER_ERROR,
                openAi(503, "{\"error\":{\"message\":\"content moderation service unavailable\"}}").getKind());
        assertEquals(UpstreamErrorKind.UPSTREAM_SERVER_ERROR,
                openAi(500, "{\"error\":{\"message\":\"safety system timeout\"}}").getKind());
    }

    @Test
    void modelNotFoundWordingMapsToModelPerm() {
        // OpenAI 官方 model_not_found 文案（"you do not have access"）→ 换渠道而不是判参数错误终态
        assertEquals(UpstreamErrorKind.MODEL_NOT_PERMITTED,
                openAi(404, "{\"error\":{\"message\":\"The model 'gpt-x' does not exist or you do not have access to it.\"}}").getKind());
    }

    @Test
    void crun403AlignedWithOpenAiFamily() {
        assertEquals(UpstreamErrorKind.BALANCE_EXHAUSTED,
                ErrorClassifier.fromCrunCode(403, "用户额度不足").getKind());
        assertEquals(UpstreamErrorKind.ACCESS_FORBIDDEN,
                ErrorClassifier.fromCrunCode(403, "forbidden").getKind());
    }

    @Test
    void httpStatusFallback() {
        assertEquals(UpstreamErrorKind.ACCESS_FORBIDDEN,
                ErrorClassifier.fromHttpStatus(403, "").getKind());
        assertEquals(UpstreamErrorKind.UPSTREAM_SERVER_ERROR,
                ErrorClassifier.fromHttpStatus(503, "").getKind());
        assertEquals(UpstreamErrorKind.UNKNOWN,
                ErrorClassifier.fromHttpStatus(418, "teapot").getKind());
    }
}
