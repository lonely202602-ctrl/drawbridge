package com.drawbridge.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标识生成测试。
 */
class IdsTest {

    @Test
    void taskIdFormatAndUniqueness() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = Ids.newTaskId();
            assertTrue(id.startsWith("tsk_"));
            assertTrue(id.length() <= 40);
            seen.add(id);
        }
        assertEquals(1000, seen.size());
    }

    @Test
    void skFormatAndHashStability() {
        String sk = Ids.newSk();
        assertTrue(sk.startsWith("sk-"));
        assertEquals(46, sk.length()); // sk- + 43 位 base64url
        assertEquals(Ids.sha256Hex(sk), Ids.sha256Hex(sk));
        assertEquals(64, Ids.sha256Hex(sk).length());
    }
}
