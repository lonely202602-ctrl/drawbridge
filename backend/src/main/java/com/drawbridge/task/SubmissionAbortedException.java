package com.drawbridge.task;

/**
 * 提交链路在未发出任何上游请求时中止（如停机强制中断落在候选循环外）。
 * 与已发请求的中断严格区分：本异常表示本次运行确定没碰过上游，任务可安全回退 SUBMITTED 重跑。
 * 由提交执行器捕获后走回退（带退避计数）。
 */
public class SubmissionAbortedException extends RuntimeException {

    public SubmissionAbortedException(String message) {
        super(message);
    }
}
