package com.drawbridge.adapter;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 上游错误归类：精确字段优先（状态码/错误码/错误类型），文案关键词兜底。
 * 分类结果决定路由行为（见 UpstreamErrorKind）。
 */
public final class ErrorClassifier {

    private static final Pattern BALANCE = Pattern.compile(
            "insufficient_quota|insufficient.balance|quota.*exceed|exceed.*quota|billing|balance"
                    + "|余额不足|账户.*欠费|欠费|额度.*不足|月度限额|spending limit", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT = Pattern.compile(
            "content_policy_violation|content.moderation|moderation.blocked|safety.system|policy.violation"
                    + "|内容审核|敏感内容|违规内容|审核未通过", Pattern.CASE_INSENSITIVE);
    private static final Pattern RATE = Pattern.compile(
            "rate.?limit|too many requests|限流|请求过于频繁", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH = Pattern.compile(
            "invalid.api.?key|invalid.token|unauthorized|authentication_error|forbidden"
                    + "|鉴权失败|密钥无效|无效的api", Pattern.CASE_INSENSITIVE);

    private ErrorClassifier() {
    }

    /** OpenAI 族（含各中转站的 OpenAI 兼容错误体 {error:{message,type,code}}）。 */
    public static UpstreamCallException fromOpenAi(int status, JsonNode body) {
        JsonNode err = body.path("error");
        String message = firstText(err.path("message"), body.path("message"), "上游返回 HTTP " + status);
        String typeAndCode = err.path("type").asText("") + " " + err.path("code").asText("");
        if (BALANCE.matcher(message).find() || BALANCE.matcher(typeAndCode).find()) {
            return new UpstreamCallException(UpstreamErrorKind.BALANCE_EXHAUSTED, message, status);
        }
        if (CONTENT.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.CONTENT_REJECTED, message, status);
        }
        if (status == 429 || RATE.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.RATE_LIMITED, message, status);
        }
        if (status == 401 || status == 403) {
            return new UpstreamCallException(UpstreamErrorKind.AUTH_FAILED, message, status);
        }
        if (status >= 500) {
            return new UpstreamCallException(UpstreamErrorKind.UPSTREAM_SERVER_ERROR, message, status);
        }
        if (status == 400 || status == 404 || status == 405 || status == 422) {
            return new UpstreamCallException(UpstreamErrorKind.PARAM_INVALID, message, status);
        }
        return new UpstreamCallException(UpstreamErrorKind.UNKNOWN, message, status);
    }

    /** Crun {code,message,data} 壳：HTTP 200 但业务 code != 200。 */
    public static UpstreamCallException fromCrunCode(int code, String message) {
        if (BALANCE.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.BALANCE_EXHAUSTED, message, code);
        }
        if (CONTENT.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.CONTENT_REJECTED, message, code);
        }
        if (code == 401 || code == 403) {
            return new UpstreamCallException(UpstreamErrorKind.AUTH_FAILED, message, code);
        }
        if (code == 402) {
            return new UpstreamCallException(UpstreamErrorKind.BALANCE_EXHAUSTED, message, code);
        }
        if (code == 429 || RATE.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.RATE_LIMITED, message, code);
        }
        if (code >= 400 && code < 450) {
            return new UpstreamCallException(UpstreamErrorKind.PARAM_INVALID, message, code);
        }
        if (code >= 500) {
            return new UpstreamCallException(UpstreamErrorKind.UPSTREAM_SERVER_ERROR, message, code);
        }
        return new UpstreamCallException(UpstreamErrorKind.UNKNOWN, message, code);
    }

    /** 纯 HTTP 状态归类（协议壳之外的情况）。 */
    public static UpstreamCallException fromHttpStatus(int status, String bodyHead) {
        String s = bodyHead == null ? "" : bodyHead;
        if (status == 401 || status == 403) {
            return new UpstreamCallException(UpstreamErrorKind.AUTH_FAILED, "上游鉴权失败（HTTP " + status + "）", status);
        }
        if (status == 429) {
            return new UpstreamCallException(UpstreamErrorKind.RATE_LIMITED, "上游限流（HTTP 429）", status);
        }
        if (status >= 500) {
            return new UpstreamCallException(UpstreamErrorKind.UPSTREAM_SERVER_ERROR,
                    "上游服务异常（HTTP " + status + "）" + head(s), status);
        }
        return new UpstreamCallException(UpstreamErrorKind.UNKNOWN,
                "上游返回 HTTP " + status + head(s), status);
    }

    private static String firstText(JsonNode a, JsonNode b, String def) {
        if (a.isTextual() && !a.asText().isBlank()) {
            return a.asText();
        }
        if (b.isTextual() && !b.asText().isBlank()) {
            return b.asText();
        }
        return def;
    }

    private static String head(String s) {
        if (s.isBlank()) {
            return "";
        }
        String trimmed = s.length() > 120 ? s.substring(0, 120) : s;
        return "：" + trimmed;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
