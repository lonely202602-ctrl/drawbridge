package com.drawbridge.infrastructure.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * multipart/form-data 请求体构造（JDK HttpClient 无内置支持，手工拼装）。
 * 用于 OpenAI images/edits 文件上传：字段 + 文件两部分，文件字段可重复（image[] 多张参考图）。
 * boundary 随机 64bit hex，与短文本字段撞串概率可忽略。
 */
public final class MultipartBody {

    /** 普通表单字段。 */
    public record Field(String name, String value) {
    }

    /** 文件字段（同名可重复出现实现多文件）。 */
    public record FilePart(String name, String filename, String contentType, byte[] bytes) {
    }

    private final String boundary;
    private final byte[] payload;

    private MultipartBody(String boundary, byte[] payload) {
        this.boundary = boundary;
        this.payload = payload;
    }

    public static MultipartBody of(List<Field> fields, List<FilePart> files) {
        String boundary = "drawbridge-" + Long.toHexString(ThreadLocalRandom.current().nextLong());
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        try {
            for (Field f : fields) {
                out.write(bytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                        + f.name() + "\"\r\n\r\n"));
                out.write(bytes(f.value() == null ? "" : f.value()));
                out.write(bytes("\r\n"));
            }
            for (FilePart f : files) {
                out.write(bytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                        + f.name() + "\"; filename=\"" + f.filename() + "\"\r\n"));
                if (f.contentType() != null && !f.contentType().isBlank()) {
                    out.write(bytes("Content-Type: " + f.contentType() + "\r\n"));
                }
                out.write(bytes("\r\n"));
                out.write(f.bytes() == null ? new byte[0] : f.bytes());
                out.write(bytes("\r\n"));
            }
            out.write(bytes("--" + boundary + "--\r\n"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("multipart 构造失败", e);
        }
        return new MultipartBody(boundary, out.toByteArray());
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public byte[] payload() {
        return payload;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
