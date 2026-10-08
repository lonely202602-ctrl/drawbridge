package com.drawbridge.route;

import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GroupMember;
import com.drawbridge.domain.entity.ModelGroup;
import com.drawbridge.domain.mapper.ChannelMapper;
import com.drawbridge.domain.mapper.GroupMemberMapper;
import com.drawbridge.domain.mapper.ModelGroupMapper;
import com.drawbridge.domain.mapper.SystemConfigMapper;
import com.drawbridge.health.ChannelHealthTracker;
import com.drawbridge.health.CooldownRegistry;
import com.drawbridge.infrastructure.AesGcmCipher;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.config.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 路由引擎测试：四策略排序 + 候选过滤（停用/自动停用/熔断/冷却）。
 */
class RouteEngineTest {

    private ChannelMapper channelMapper;
    private ModelGroupMapper groupMapper;
    private GroupMemberMapper memberMapper;
    private ChannelConfigService configService;
    private CooldownRegistry cooldown;

    private Channel channelA;
    private Channel channelB;
    private Channel channelC;

    @BeforeEach
    void setUp() {
        channelMapper = mock(ChannelMapper.class);
        groupMapper = mock(ModelGroupMapper.class);
        memberMapper = mock(GroupMemberMapper.class);

        channelA = channel(1L, 1);
        channelB = channel(2L, 1);
        channelC = channel(3L, 1);
        when(channelMapper.selectList(any())).thenReturn(List.of(channelA, channelB, channelC));

        ModelGroup group = new ModelGroup();
        group.setId(10L);
        group.setGroupCode("image2.5");
        group.setStrategy("FAILOVER");
        group.setEnabled(1);
        when(groupMapper.selectList(any())).thenReturn(List.of(group));

        when(memberMapper.selectList(any())).thenReturn(List.of(
                member(10L, 1L, 0), member(10L, 2L, 1), member(10L, 3L, 2)));

        configService = new ChannelConfigService(channelMapper, groupMapper, memberMapper,
                new AesGcmCipher("route-test"));
        configService.refresh();

        cooldown = new CooldownRegistry(new SystemConfigService(mock(SystemConfigMapper.class)));
    }

    private RouteEngine engine(String strategy) {
        configService.groupByCode("image2.5").setStrategy(strategy);
        return new RouteEngine(configService,
                new ChannelHealthTracker(new SystemConfigService(mock(SystemConfigMapper.class))),
                cooldown);
    }

    private List<Long> ids(List<RouteEngine.Candidate> candidates) {
        return candidates.stream().map(c -> c.channel().getId()).toList();
    }

    @Test
    void failoverKeepsSortOrder() {
        assertEquals(List.of(1L, 2L, 3L), ids(engine("FAILOVER").candidatesFor("image2.5")));
    }

    @Test
    void pollingRotates() {
        RouteEngine e = engine("POLLING");
        assertEquals(List.of(1L, 2L, 3L), ids(e.candidatesFor("image2.5")));
        assertEquals(List.of(2L, 3L, 1L), ids(e.candidatesFor("image2.5")));
        assertEquals(List.of(3L, 1L, 2L), ids(e.candidatesFor("image2.5")));
        assertEquals(List.of(1L, 2L, 3L), ids(e.candidatesFor("image2.5")));
    }

    @Test
    void randomKeepsSameCandidateSet() {
        RouteEngine e = engine("RANDOM");
        for (int i = 0; i < 20; i++) {
            assertEquals(Set.of(1L, 2L, 3L), Set.copyOf(ids(e.candidatesFor("image2.5"))));
        }
    }

    @Test
    void weightedFavorsHeavyMember() {
        when(memberMapper.selectList(any())).thenReturn(List.of(
                member(10L, 1L, 0, 1000, 1), member(10L, 2L, 1, 1, 1), member(10L, 3L, 2, 1, 1)));
        configService.refresh();
        RouteEngine e = engine("WEIGHTED");
        int firstIsA = 0;
        for (int i = 0; i < 200; i++) {
            if (ids(e.candidatesFor("image2.5")).getFirst() == 1L) {
                firstIsA++;
            }
        }
        assertTrue(firstIsA > 180, "权重 1000:1:1 时 A 应几乎总在首位，实际 " + firstIsA + "/200");
    }

    @Test
    void disabledChannelFiltered() {
        channelB.setEnabled(0);
        configService.refresh();
        assertEquals(List.of(1L, 3L), ids(engine("FAILOVER").candidatesFor("image2.5")));
    }

    @Test
    void balanceDisabledStatusFiltered() {
        channelB.setStatus(4);
        configService.refresh();
        assertEquals(List.of(1L, 3L), ids(engine("FAILOVER").candidatesFor("image2.5")));
    }

    @Test
    void breakerOpenChannelFiltered() {
        ChannelHealthTracker tracker = new ChannelHealthTracker(
                new SystemConfigService(mock(SystemConfigMapper.class)));
        // 熔断评估发生在失败事件上：9 成功 + 11 失败 → 失败率 55% 触发熔断
        for (int i = 0; i < 9; i++) {
            tracker.recordSuccess(2L);
        }
        for (int i = 0; i < 11; i++) {
            tracker.recordFailure(2L);
        }
        // 渠道 2 熔断（快照 status 仍是 1，但运行态熔断 OPEN）
        RouteEngine e = new RouteEngine(configService, tracker, cooldown);
        assertEquals(List.of(1L, 3L), ids(e.candidatesFor("image2.5")));
    }

    @Test
    void disabledGroupReturnsEmpty() {
        configService.groupByCode("image2.5").setEnabled(0);
        configService.refresh();
        assertTrue(engine("FAILOVER").candidatesFor("image2.5").isEmpty());
        assertTrue(engine("FAILOVER").candidatesFor("no-such-group").isEmpty());
    }

    @Test
    void coolingChannelFiltered() {
        cooldown.cool(2L);
        assertEquals(List.of(1L, 3L), ids(engine("FAILOVER").candidatesFor("image2.5")));
    }

    @Test
    void memberDisabledFiltered() {
        when(memberMapper.selectList(any())).thenReturn(List.of(
                member(10L, 1L, 0), member(10L, 2L, 1, 1, 0), member(10L, 3L, 2)));
        configService.refresh();
        assertEquals(List.of(1L, 3L), ids(engine("FAILOVER").candidatesFor("image2.5")));
    }

    private Channel channel(long id, int status) {
        Channel c = new Channel();
        c.setId(id);
        c.setName("ch-" + id);
        c.setProtocol("OPENAI_IMAGES_SYNC");
        c.setBaseUrl("https://upstream-" + id + ".example.com/v1");
        c.setEnabled(1);
        c.setStatus(status);
        return c;
    }

    private GroupMember member(long groupId, long channelId, int sortOrder) {
        return member(groupId, channelId, sortOrder, 1, 1);
    }

    private GroupMember member(long groupId, long channelId, int sortOrder, int weight, int enabled) {
        GroupMember m = new GroupMember();
        m.setId(groupId * 100 + channelId);
        m.setGroupId(groupId);
        m.setChannelId(channelId);
        m.setUpstreamModel("model-" + channelId);
        m.setWeight(weight);
        m.setSortOrder(sortOrder);
        m.setEnabled(enabled);
        return m;
    }
}
