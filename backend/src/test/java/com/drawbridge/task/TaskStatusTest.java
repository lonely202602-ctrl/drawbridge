package com.drawbridge.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务状态机全流转测试。
 */
class TaskStatusTest {

    @Test
    void submittedCanGoRunningOrFailed() {
        assertEquals(TaskStatus.RUNNING, TaskStatus.transit(TaskStatus.SUBMITTED, TaskStatus.RUNNING));
        assertEquals(TaskStatus.FAILED, TaskStatus.transit(TaskStatus.SUBMITTED, TaskStatus.FAILED));
        assertNull(TaskStatus.transit(TaskStatus.SUBMITTED, TaskStatus.SUCCEEDED));
    }

    @Test
    void runningCanGoAnywhereForward() {
        assertEquals(TaskStatus.SUCCEEDED, TaskStatus.transit(TaskStatus.RUNNING, TaskStatus.SUCCEEDED));
        assertEquals(TaskStatus.FAILED, TaskStatus.transit(TaskStatus.RUNNING, TaskStatus.FAILED));
        assertEquals(TaskStatus.RUNNING, TaskStatus.transit(TaskStatus.RUNNING, TaskStatus.RUNNING));
    }

    @Test
    void terminalIsAbsorbing() {
        assertNull(TaskStatus.transit(TaskStatus.SUCCEEDED, TaskStatus.RUNNING));
        assertNull(TaskStatus.transit(TaskStatus.SUCCEEDED, TaskStatus.FAILED));
        assertNull(TaskStatus.transit(TaskStatus.FAILED, TaskStatus.RUNNING));
        assertNull(TaskStatus.transit(TaskStatus.FAILED, TaskStatus.SUCCEEDED));
        assertTrue(TaskStatus.SUCCEEDED.isTerminal());
        assertTrue(TaskStatus.FAILED.isTerminal());
        assertFalse(TaskStatus.RUNNING.isTerminal());
        assertFalse(TaskStatus.SUBMITTED.isTerminal());
    }

    @Test
    void ofToleratesBadValue() {
        assertEquals(TaskStatus.SUBMITTED, TaskStatus.of(null));
        assertEquals(TaskStatus.SUBMITTED, TaskStatus.of("GARBAGE"));
        assertEquals(TaskStatus.RUNNING, TaskStatus.of("RUNNING"));
    }
}
