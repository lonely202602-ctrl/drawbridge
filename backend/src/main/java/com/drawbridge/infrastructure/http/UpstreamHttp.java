package com.drawbridge.infrastructure.http;

import com.drawbridge.adapter.UpstreamCallException;
import com.drawbridge.common.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 上游 HTTP 客户端（JDK HttpClient，虚拟线程友好）。
 * 只负责传输：网络层异常统一归为 NETWORK_TIMEOUT；协议层错误由各适配器解析响应后归类。
 */
@Slf4j
@Component
public class UpstreamHttp {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public HttpResult getJson(String url, Map<String, String> headers, int timeoutMs) {
        return exchange("GET", url, headers, null, timeoutMs);
    }

    public HttpResult postJson(String url, Map<String, String> headers, String jsonBody, int timeoutMs) {
        return exchange("POST", url, headers, jsonBody, timeoutMs);
    }

    public HttpResult exchange(String method, String url, Map<String, String> headers,
                               String jsonBody, int timeoutMs) {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw UpstreamCallException.network("上游地址不合法");
        }
        builder.timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .header("Accept", "application/json");
        if (headers != null) {
            headers.forEach(builder::header);
        }
        switch (method) {
            case "POST" -> builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody));
            case "PUT" -> builder.header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody));
            default -> builder.GET();
        }
        try {
            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (log.isDebugEnabled()) {
                log.debug("上游响应: url={} status={} bodyHead={}",
                        url, response.statusCode(), head(response.body()));
            }
            return new HttpResult(response.statusCode(), response.body());
        } catch (java.net.http.HttpTimeoutException e) {
            throw UpstreamCallException.network("上游请求超时（" + timeoutMs + "ms）");
        } catch (java.io.IOException e) {
            throw UpstreamCallException.network("上游连接失败: " + rootMessage(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 停机强制中断不是渠道故障：独立类别，不喂熔断、不计渠道失败
            throw new UpstreamCallException(com.drawbridge.adapter.UpstreamErrorKind.SHUTDOWN_INTERRUPT,
                    "请求被停机中断", null);
        }
    }

    /** 解析响应体为 JSON 节点；非合法 JSON 抛异常——2xx 归 BAD_RESPONSE（上游可能已出图，不可换渠道重发），其它状态归 UNKNOWN。 */
    public static com.fasterxml.jackson.databind.JsonNode parseBody(int status, String body) {
        try {
            return JsonUtil.MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            com.drawbridge.adapter.UpstreamErrorKind kind = status >= 200 && status < 300
                    ? com.drawbridge.adapter.UpstreamErrorKind.BAD_RESPONSE
                    : com.drawbridge.adapter.UpstreamErrorKind.UNKNOWN;
            throw new UpstreamCallException(kind, "上游响应非 JSON（HTTP " + status + "）", status);
        }
    }

    private static String head(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300);
    }

    private static String rootMessage(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    public record HttpResult(int status, String body) {
    }
}
