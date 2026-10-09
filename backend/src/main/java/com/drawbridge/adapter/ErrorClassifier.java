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
    /** 输入文件不被支持（如参考图格式/来源不合法）：属请求问题，重试无意义 */
    private static final Pattern FILE_UNSUPPORTED = Pattern.compile(
            "file type not supported|unsupported file|invalid file|文件类型不支持|不支持的文件", Pattern.CASE_INSENSITIVE);

    /** Key 有效、但该模型无权限：属模型级问题，换渠道重试而不是停用渠道 */
    private static final Pattern MODEL_PERM = Pattern.compile(
            "not authorized to access this model|access to (this )?model.*(denied|not authorized)"
                    + "|does not have access|无权访问该模型|未开通该模型", Pattern.CASE_INSENSITIVE);

    /** 渠道当前无可用账号/容量服务此模型（如 new-api 系「无可用渠道」、uuapi「not supported by any account」）：
     *  属渠道侧容量波动，换渠道重试而不是判参数错误（修复：uuapi 没号时任务直接失败、备胎不转移）。 */
    private static final Pattern MODEL_UNAVAILABLE = Pattern.compile(
            "not supported by any|no available channel|无可用渠道|无可用账号|无可用模型|no available model",
            Pattern.CASE_INSENSITIVE);

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
        if (MODEL_PERM.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.MODEL_NOT_PERMITTED, message, status);
        }
        if (MODEL_UNAVAILABLE.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.MODEL_UNAVAILABLE, message, status);
        }
        if (FILE_UNSUPPORTED.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.PARAM_INVALID, message, status);
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
        if (MODEL_PERM.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.MODEL_NOT_PERMITTED, message, code);
        }
        if (MODEL_UNAVAILABLE.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.MODEL_UNAVAILABLE, message, code);
        }
        if (FILE_UNSUPPORTED.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.PARAM_INVALID, message, code);
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
