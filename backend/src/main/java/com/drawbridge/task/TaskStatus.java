package com.drawbridge.task;

/**
 * 任务状态机：SUBMITTED → RUNNING → SUCCEEDED/FAILED（终态不可逆）。
 */
public enum TaskStatus {
    SUBMITTED,
    RUNNING,
    SUCCEEDED,
    FAILED;

    public static TaskStatus of(String value) {
        try {
            return value == null ? SUBMITTED : TaskStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return SUBMITTED;
        }
    }

    /** 校验流转合法性，非法返回 null（调用方按需忽略或报错）。 */
    public static TaskStatus transit(TaskStatus from, TaskStatus to) {
        boolean legal = switch (from) {
            case SUBMITTED -> to == RUNNING || to == FAILED;
            case RUNNING -> to == RUNNING || to == SUCCEEDED || to == FAILED;
            case SUCCEEDED, FAILED -> false;
        };
        return legal ? to : null;
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
