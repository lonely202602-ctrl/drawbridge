package com.drawbridge.task;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 全局领取锁：COUNT（槽位核查）+ UPDATE（领取/换绑）在锁内串行，
 * 账号容量与全网在途上限由此同时精确保证（按账号分锁管不住全局上限）。
 * 必须用 ReentrantLock——Java 21 下 synchronized 会把虚拟线程钉在载体线程上。
 * 领取路径禁止包外层事务：事务提交晚于放锁（或快照早于他人提交）会让锁内核查失效。
 */
@Component
public class DispatchLock {

    private final ReentrantLock lock = new ReentrantLock();

    public <T> T supply(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void run(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
