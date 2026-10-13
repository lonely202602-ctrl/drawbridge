package com.drawbridge.infrastructure.http;

import com.drawbridge.adapter.UpstreamCallException;
import com.drawbridge.adapter.UpstreamErrorKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 业务方参考图代下载：对外契约只收公网 URL，但 OpenAI images/edits（multipart 文件）与
 * Gemini 原生（inlineData base64）两族上游要的是图片本体，网关统一代拉。
 * 失败归 REF_IMAGE_FAILED：可换渠道重试（图床抖动换个时刻就好了），但不喂熔断不停渠道——
 * 锅在业务方的图床，惩罚渠道只会误伤健康渠道。
 */
@Slf4j
@Component
public class RefImageFetcher {

    /** 单张上限 20MB：apiyi edits 建议压到 1.5MB、官方上限 50MB、Gemini 单图 7MB，20MB 覆盖全部合规场景。 */
    static final int MAX_BYTES = 20 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 拉取结果：字节 + 嗅探出的 MIME + 派生文件名（multipart 用）。 */
    public record Fetched(byte[] bytes, String contentType, String filename) {
    }

    public Fetched fetch(String url, int timeoutMs) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .header("Accept", "image/*")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw refFailed("参考图地址不合法: " + url);
        }
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw refFailed("参考图下载失败: HTTP " + response.statusCode() + " " + url);
            }
            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0) {
                throw refFailed("参考图下载失败: 空文件 " + url);
            }
            if (bytes.length > MAX_BYTES) {
                throw refFailed("参考图超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限 " + url);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String sniffed = sniffBytes(bytes);
            if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
                // 头不认账按魔数来；魔数也不认识才拒绝（上游普遍只收 png/jpeg/webp）
                if (sniffed == null) {
                    throw refFailed("参考图不是可识别的图片格式 " + url);
                }
                contentType = "image/" + sniffed;
            }
            return new Fetched(bytes, contentType.split(";")[0].trim(), filenameOf(url, sniffed));
        } catch (java.net.http.HttpTimeoutException e) {
            throw refFailed("参考图下载超时 " + url);
        } catch (java.io.IOException e) {
            throw refFailed("参考图下载失败: " + rootMessage(e) + " " + url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamCallException(UpstreamErrorKind.SHUTDOWN_INTERRUPT, "参考图下载被停机中断", null);
        }
    }

    /** 魔数嗅探：png / jpeg / webp / gif；认不出返回 null（不瞎猜）。 */
    private static String sniffBytes(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "gif";
        }
        return null;
    }

    /** 文件名从 URL 路径取末段（去 query），无扩展名按 MIME 补；空路径回退 ref<N>.<ext>。 */
    private static String filenameOf(String url, String sniffed) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.isBlank()) {
            name = "ref." + (sniffed == null ? "png" : sniffed);
        }
        if (!name.contains(".")) {
            name = name + "." + (sniffed == null ? "png" : sniffed);
        }
        return name.length() > 80 ? name.substring(name.length() - 80) : name;
    }

    private static UpstreamCallException refFailed(String message) {
        return new UpstreamCallException(UpstreamErrorKind.REF_IMAGE_FAILED, message, null);
    }

    private static String rootMessage(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
