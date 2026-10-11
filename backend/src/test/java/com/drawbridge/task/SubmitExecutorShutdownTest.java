package com.drawbridge.task;

import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 优雅停机单测：独立构造执行器（绝不碰 Spring 上下文里共享的那个——
 * 在集成测试里关掉它会让后续用例全部 RejectedExecutionException）。
 */
class SubmitExecutorShutdownTest {

    @org.junit.jupiter.api.BeforeEach
    void initMybatisLambdaCache() {
        // 纯单测没有 MP 启动过程，lambda wrapper 需要 TableInfo 缓存（否则 kick 里构造领取条件就抛异常）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.drawbridge.domain.entity.GenerateTask.class);
    }

    @Test
    void shutdownWaitsForInFlightSubmitToFinish() throws Exception {
        GenerateTaskMapper taskMapper = mock(GenerateTaskMapper.class);
        when(taskMapper.update(any(), any())).thenReturn(1);
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getLong(any(ConfigKeys.class))).thenReturn(60_000L);
        when(config.getInt(any(ConfigKeys.class))).thenReturn(4);

        SubmitChain chain = mock(SubmitChain.class);
        CountDownLatch chainStarted = new CountDownLatch(1);
        CountDownLatch releaseChain = new CountDownLatch(1);
        doAnswer(inv -> {
            chainStarted.countDown();
            releaseChain.await(10, TimeUnit.SECONDS);
            return null;
        }).when(chain).run(anyLong());

        SubmitExecutor executor = new SubmitExecutor(taskMapper, chain, mock(TaskFinisher.class), config);
        executor.kick(1L);
        assertTrue(chainStarted.await(5, TimeUnit.SECONDS), "在途提交应已开始执行");

        // 300ms 后放行链路；shutdown 必须等它跑完才返回（而不是立刻掐断）
        ScheduledExecutorService releaser = Executors.newSingleThreadScheduledExecutor();
        releaser.schedule(releaseChain::countDown, 300, TimeUnit.MILLISECONDS);
        long t0 = System.nanoTime();
        executor.shutdown();
        long waitedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(waitedMs >= 250, "shutdown 应等在途提交收尾，实际只等了 " + waitedMs + "ms");
        releaser.shutdownNow();
    }
}
