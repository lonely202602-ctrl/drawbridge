package com.drawbridge.task;

import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.entity.GroupMember;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.health.AccountRegistry;
import com.drawbridge.health.ConcurrencyGuard;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import com.drawbridge.route.RouteEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 优雅停机单测：独立构造派发器（绝不碰 Spring 上下文里共享的那个——
 * 在集成测试里关掉它会让后续用例全部 RejectedExecutionException）。
 */
class SubmitExecutorShutdownTest {

    @org.junit.jupiter.api.BeforeEach
    void initMybatisLambdaCache() {
        // 纯单测没有 MP 启动过程，lambda wrapper 需要 TableInfo 缓存（否则领取条件构造就抛异常）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.drawbridge.domain.entity.GenerateTask.class);
    }

    @Test
    void shutdownWaitsForInFlightSubmitToFinish() throws Exception {
        GenerateTaskMapper taskMapper = mock(GenerateTaskMapper.class);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(taskMapper.selectById(anyLong())).thenReturn(submittedTask());
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectOne(any())).thenReturn(submittedTask());
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getLong(any(ConfigKeys.class))).thenReturn(60_000L);
        when(config.getInt(any(ConfigKeys.class))).thenReturn(4);

        // 槽位全通：容量 5、在跑 0、账号登记齐全
        ConcurrencyGuard guard = mock(ConcurrencyGuard.class);
        when(guard.capacityOf(anyString())).thenReturn(5);
        when(guard.runningOfAccount(anyString())).thenReturn(0);
        when(guard.runningTotal()).thenReturn(0);
        AccountRegistry registry = mock(AccountRegistry.class);
        when(registry.capacityOf(anyString())).thenReturn(5);

        // 候选：一个带账号键的渠道
        Channel channel = new Channel();
        channel.setId(1L);
        channel.setAccountKey("ak_test");
        GroupMember member = new GroupMember();
        member.setId(11L);
        member.setChannelId(1L);
        RouteEngine routeEngine = mock(RouteEngine.class);
        when(routeEngine.candidateState(anyString())).thenReturn(new RouteEngine.GroupRouting(
                List.of(new RouteEngine.Candidate(channel, member)), RouteEngine.Availability.OK,
                com.drawbridge.route.RouteStrategy.FAILOVER, 10L));

        SlotClaims slotClaims = mock(SlotClaims.class);
        when(slotClaims.claimSubmitted(anyLong(), any(Channel.class)))
                .thenReturn(SlotClaims.ClaimResult.CLAIMED);

        // 快照选路用（kick 快路径的 freeSlotsByAccount 读快照；快照里要有带账号键的渠道才有空槽）
        com.drawbridge.infrastructure.config.ChannelConfigService configService =
                mock(com.drawbridge.infrastructure.config.ChannelConfigService.class);
        when(configService.current()).thenReturn(new com.drawbridge.infrastructure.config.ChannelConfigService
                .Snapshot(java.util.Map.of(1L, channel), java.util.Map.of(), java.util.Map.of()));

        SubmitChain chain = mock(SubmitChain.class);
        CountDownLatch chainStarted = new CountDownLatch(1);
        CountDownLatch releaseChain = new CountDownLatch(1);
        doAnswer(inv -> {
            chainStarted.countDown();
            releaseChain.await(10, TimeUnit.SECONDS);
            return null;
        }).when(chain).run(anyLong());

        SubmitExecutor executor = new SubmitExecutor(taskMapper, chain, mock(TaskFinisher.class), config,
                configService, guard, routeEngine, slotClaims);
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

    private GenerateTask submittedTask() {
        GenerateTask t = new GenerateTask();
        t.setId(1L);
        t.setTaskId("task_shutdown");
        t.setGroupCode("g");
        t.setStatus("SUBMITTED");
        t.setCreatedAt(java.time.LocalDateTime.now());
        return t;
    }
}
