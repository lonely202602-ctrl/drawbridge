package com.drawbridge.task;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提交等待器：业务方提交请求在"上游受理/任务终态"两点醒来返回——
 * 异步上游秒回任务号，同步上游在请求内等出图。等待有超时，超时后照样返回当前任务状态。
 * 若进程崩溃，任务仍在 DB 队列里由流水线续跑，业务方用查询接口即可拿到结果。
 */
@Component
public class MilestoneRegistry {

    private final Map<String, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();

    /** 注册等待（提交请求线程调用）。 */
    public CompletableFuture<Void> register(String taskId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        waiters.put(taskId, future);
        return future;
    }

    /** 唤醒等待者（流水线在受理/终态时调用，幂等）。 */
    public void release(String taskId) {
        CompletableFuture<Void> future = waiters.remove(taskId);
        if (future != null) {
            future.complete(null);
        }
    }
}
