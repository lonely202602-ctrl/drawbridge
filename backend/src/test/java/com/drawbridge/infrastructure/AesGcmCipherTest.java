package com.drawbridge.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AES-GCM 加解密测试。
 */
class AesGcmCipherTest {

    @Test
    void roundtrip() {
        AesGcmCipher cipher = new AesGcmCipher("test-secret");
        String plain = "sk-upstream-api-key-中文-123";
        String enc = cipher.encrypt(plain);
        assertNotEquals(plain, enc);
        assertEquals(plain, cipher.decrypt(enc));
    }

    @Test
    void sameCipherDifferentCiphertextEachTime() {
        AesGcmCipher cipher = new AesGcmCipher("test-secret");
        assertNotEquals(cipher.encrypt("abc"), cipher.encrypt("abc"));
    }

    @Test
    void wrongSecretFailsToDecrypt() {
        String enc = new AesGcmCipher("secret-a").encrypt("data");
        assertThrows(IllegalStateException.class, () -> new AesGcmCipher("secret-b").decrypt(enc));
    }
}
