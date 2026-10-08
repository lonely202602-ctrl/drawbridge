package com.drawbridge.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 标识生成：对外任务号、业务方 SK、哈希。
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    private Ids() {
    }

    /** 对外任务号：tsk_ + 时间戳36进制 + 随机段，全局唯一且不带自增信息。 */
    public static String newTaskId() {
        long ts = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("tsk_").append(Long.toString(ts, 36)).append('_');
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 业务方 SK：sk- + 43 位 base64url（32 字节熵），只在创建时展示一次。 */
    public static String newSk() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "sk-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
