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
            "insufficient[_\\w]*quota|insufficient\\s+quota|insufficient.balance"
                    + "|quota.*exceed|exceed.*quota|billing|balance"
                    + "|余额不足|欠费|额度不足|额度已用尽|月度限额|spending limit", Pattern.CASE_INSENSITIVE);
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

    /** 5xx 状态层的窄审核识别：只认明确判定措辞，不认子系统名——
     *  "content moderation service unavailable" 这类 5xx 文案是服务故障，不是审核拒绝 */
    private static final Pattern CONTENT_EXPLICIT = Pattern.compile(
            "content_policy_violation|violates.*policy|审核未通过|违规内容", Pattern.CASE_INSENSITIVE);

    /** Key 有效、但该模型无权限：属模型级问题，换渠道重试而不是停用渠道 */
    private static final Pattern MODEL_PERM = Pattern.compile(
            "not authorized to access this model|access to (this )?model.*(denied|not authorized)"
                    + "|does not have access|do not have access|无权访问该模型|未开通该模型", Pattern.CASE_INSENSITIVE);

    /** 渠道当前无可用账号/容量服务此模型（如 new-api 系「无可用渠道」、uuapi「not supported by any account」）：
     *  属渠道侧容量波动，换渠道重试而不是判参数错误（修复：uuapi 没号时任务直接失败、备胎不转移）。 */
    private static final Pattern MODEL_UNAVAILABLE = Pattern.compile(
            "not supported by any|no available channel|无可用渠道|无可用账号|无可用模型|no available model",
            Pattern.CASE_INSENSITIVE);

    /** 无歧义的没米措辞（403/429 状态层的窄识别）：不用贪婪跨句模式，
     *  防 "insufficient capacity … rate limit quota exceeded" 这类限流话术被误判成没米停渠道 */
    private static final Pattern BALANCE_EXPLICIT = Pattern.compile(
            "余额不足|欠费|额度不足|额度已用尽"
                    + "|insufficient[_\\w]*quota|insufficient\\s+quota|quota is not enough",
            Pattern.CASE_INSENSITIVE);

    private ErrorClassifier() {
    }

    /**
     * OpenAI 族（含各中转站的 OpenAI 兼容错误体 {error:{message,type,code}}）。
     * 分类三层，文案只做最后兜底（防 "load balancer"/"billing service" 之类文案误伤）：
     * ① 结构化业务字段（error.type/error.code，如 insufficient_quota）——最精确；
     * ② HTTP 状态码——权威信号（429 限流/401 Key 失效/403 拒绝访问/5xx 服务异常）；
     * ③ 文案关键词——前两层都不认识时才参与。
     */
    public static UpstreamCallException fromOpenAi(int status, JsonNode body) {
        JsonNode err = body.path("error");
        String message = firstText(err.path("message"), body.path("message"), "上游返回 HTTP " + status);
        String typeAndCode = err.path("type").asText("") + " " + err.path("code").asText("");
        // ① 结构化业务字段（insufficient_quota 常伴 429 回来，必须在状态码前拦下，否则没米会被当限流）
        if (BALANCE.matcher(typeAndCode).find()) {
            return new UpstreamCallException(UpstreamErrorKind.BALANCE_EXHAUSTED, message, status);
        }
        if (CONTENT.matcher(typeAndCode).find()) {
            return new UpstreamCallException(UpstreamErrorKind.CONTENT_REJECTED, message, status);
        }
        // ② 状态码（①已拦结构化没米信号；这里只留无歧义的窄识别，防真没米/容量波动被埋掉）
        if (status == 429) {
            if (BALANCE_EXPLICIT.matcher(message).find()) {
                return new UpstreamCallException(UpstreamErrorKind.BALANCE_EXHAUSTED, message, status);
            }
            return new UpstreamCallException(UpstreamErrorKind.RATE_LIMITED, message, status);
        }
        if (status == 401) {
            return new UpstreamCallException(UpstreamErrorKind.AUTH_FAILED, message, status);
        }
        if (status == 403) {
            if (BALANCE_EXPLICIT.matcher(message).find()) {
                return new UpstreamCallException(UpstreamErrorKind.BALANCE_EXHAUSTED, message, status);
            }
            if (MODEL_PERM.matcher(message).find()) {
                // 模型级权限（Key 有效，单模型无权）：换渠道，不停渠道
                return new UpstreamCallException(UpstreamErrorKind.MODEL_NOT_PERMITTED, message, status);
            }
            if (CONTENT.matcher(message).find()) {
                // 中转平台用 403 回内容拒绝：判终态，不把同一个敏感词打满所有渠道
                return new UpstreamCallException(UpstreamErrorKind.CONTENT_REJECTED, message, status);
            }
            return new UpstreamCallException(UpstreamErrorKind.ACCESS_FORBIDDEN, message, status);
        }
        if (status >= 500) {
            if (MODEL_UNAVAILABLE.matcher(message).find()) {
                // new-api 系 503「无可用渠道」：容量波动不是故障，转移而不喂熔断
                return new UpstreamCallException(UpstreamErrorKind.MODEL_UNAVAILABLE, message, status);
            }
            if (CONTENT_EXPLICIT.matcher(message).find()) {
                // 5xx 带明确审核判定措辞：终态（只认判定语，"moderation service unavailable" 这类子系统故障不算）
                return new UpstreamCallException(UpstreamErrorKind.CONTENT_REJECTED, message, status);
            }
            return new UpstreamCallException(UpstreamErrorKind.UPSTREAM_SERVER_ERROR, message, status);
        }
        // ③ 文案兜底
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
        if (RATE.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.RATE_LIMITED, message, status);
        }
        if (BALANCE.matcher(message).find()) {
            return new UpstreamCallException(UpstreamErrorKind.BALANCE_EXHAUSTED, message, status);
        }
        if (status == 400 || status == 404 || status == 405 || status == 422) {
            return new UpstreamCallException(UpstreamErrorKind.PARAM_INVALID, message, status);
        }
        return new UpstreamCallException(UpstreamErrorKind.UNKNOWN, message, status);
    }

    /**
     * Crun {code,message,data} 壳：HTTP 200 但业务 code != 200。
     * 官方「常见错误码」直映射：401 鉴权 / 404 任务未找到 / 422 参数 / 429 限流 / 451 内容政策；
     * 枚举之外的先走文案关键词精确识别，再落状态段（402 没米、4xx 参数、5xx 服务异常）。
     */
    public static UpstreamCallException fromCrunCode(int code, String message) {
        if (code == 401) {
            return new UpstreamCallException(UpstreamErrorKind.AUTH_FAILED, message, code);
        }
        if (code == 404) {
            return new UpstreamCallException(UpstreamErrorKind.TASK_NOT_FOUND, message, code);
        }
        if (code == 451) {
            return new UpstreamCallException(UpstreamErrorKind.CONTENT_REJECTED, message, code);
        }
        if (code == 429) {
            return new UpstreamCallException(UpstreamErrorKind.RATE_LIMITED, message, code);
        }
        if (code == 403) {
            // 与 OpenAI 族对齐：窄没米 → 模型权限 → 审核拒绝 → 拒绝访问（喂熔断换渠道），不判参数错误终态
            if (BALANCE_EXPLICIT.matcher(message).find()) {
                return new UpstreamCallException(UpstreamErrorKind.BALANCE_EXHAUSTED, message, code);
            }
            if (MODEL_PERM.matcher(message).find()) {
                return new UpstreamCallException(UpstreamErrorKind.MODEL_NOT_PERMITTED, message, code);
            }
            if (CONTENT.matcher(message).find()) {
                return new UpstreamCallException(UpstreamErrorKind.CONTENT_REJECTED, message, code);
            }
            return new UpstreamCallException(UpstreamErrorKind.ACCESS_FORBIDDEN, message, code);
        }
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
        if (code == 402) {
            return new UpstreamCallException(UpstreamErrorKind.BALANCE_EXHAUSTED, message, code);
        }
        if (RATE.matcher(message).find()) {
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
        if (status == 401) {
            return new UpstreamCallException(UpstreamErrorKind.AUTH_FAILED, "上游鉴权失败（HTTP " + status + "）", status);
        }
        if (status == 403) {
            return new UpstreamCallException(UpstreamErrorKind.ACCESS_FORBIDDEN,
                    "上游拒绝访问（HTTP 403，可能是 WAF/网关拦截）" + head(s), status);
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
