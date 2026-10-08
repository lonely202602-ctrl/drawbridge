package com.drawbridge.adapter;

import com.drawbridge.common.JsonUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void httpStatusFallback() {
        assertEquals(UpstreamErrorKind.AUTH_FAILED,
                ErrorClassifier.fromHttpStatus(403, "").getKind());
        assertEquals(UpstreamErrorKind.UPSTREAM_SERVER_ERROR,
                ErrorClassifier.fromHttpStatus(503, "").getKind());
        assertEquals(UpstreamErrorKind.UNKNOWN,
                ErrorClassifier.fromHttpStatus(418, "teapot").getKind());
    }
}
