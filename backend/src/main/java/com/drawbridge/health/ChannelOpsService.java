package com.drawbridge.health;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GroupMember;
import com.drawbridge.domain.mapper.ChannelMapper;
import com.drawbridge.domain.mapper.GroupMemberMapper;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.route.RouteEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 渠道运维状态机：自动停用（余额耗尽/Key 失效）与恢复。
 * 停用持久化到 DB + 刷新配置快照（转发链路立刻剔除）+ 联动停用分组成员；恢复由管理端一键触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelOpsService {

    public static final int STATUS_MANUAL_DISABLED = 0;
    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_BALANCE_DISABLED = 4;

    private final ChannelMapper channelMapper;
    private final GroupMemberMapper memberMapper;
    private final ChannelConfigService configService;
    private final RouteEngine routeEngine;

    /** 余额耗尽 / Key 失效：自动停用并告警。 */
    public void autoDisable(Long channelId, String reason) {
        channelMapper.update(null, Wrappers.<Channel>lambdaUpdate()
                .eq(Channel::getId, channelId)
                .set(Channel::getStatus, STATUS_BALANCE_DISABLED)
                .set(Channel::getStatusReason, reason)
                .set(Channel::getStatusTime, LocalDateTime.now()));
        int members = cascadeDisableMembers(channelId);
        configService.refresh();
        log.error("[drawbridge][告警] 渠道已自动停用: channelId={}, 原因={} —— 请充值后到管理端一键恢复",
                channelId, reason);
        if (members > 0) {
            log.warn("渠道停用联动: channelId={} 已停用其分组成员 {} 个并滑至各分组队尾", channelId, members);
        }
    }

    /** 管理端手动启用：清停用状态恢复调度。分组成员不会自动恢复，需在分组里逐个“启”。 */
    public void enable(Long channelId) {
        channelMapper.update(null, Wrappers.<Channel>lambdaUpdate()
                .eq(Channel::getId, channelId)
                .set(Channel::getStatus, STATUS_NORMAL)
                .set(Channel::getStatusReason, null)
                .set(Channel::getStatusTime, LocalDateTime.now())
                .set(Channel::getEnabled, 1));
        configService.refresh();
        log.info("渠道已启用: channelId={}", channelId);
    }

    public void disable(Long channelId, String reason) {
        channelMapper.update(null, Wrappers.<Channel>lambdaUpdate()
                .eq(Channel::getId, channelId)
                .set(Channel::getStatus, STATUS_MANUAL_DISABLED)
                .set(Channel::getStatusReason, reason)
                .set(Channel::getStatusTime, LocalDateTime.now())
                .set(Channel::getEnabled, 0));
        int members = cascadeDisableMembers(channelId);
        configService.refresh();
        log.info("渠道已手动停用: channelId={}, reason={}, 联动停用成员 {} 个", channelId, reason, members);
    }

    /**
     * 渠道停用联动：引用该渠道的分组成员全部停用，并滑到所在分组队尾。
     * 排序重写为“启用成员在前（原相对顺序不变），停用成员在后（原相对顺序不变）”，
     * 多次停用/多个停用成员都保持稳定，不会来回跳动。
     *
     * @return 本次联动停用的成员数
     */
    private int cascadeDisableMembers(Long channelId) {
        List<GroupMember> affected = memberMapper.selectList(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getChannelId, channelId)
                .eq(GroupMember::getEnabled, 1));
        if (affected.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        for (GroupMember m : affected) {
            m.setEnabled(0);
            m.setUpdatedAt(now);
            memberMapper.updateById(m);
        }
        for (Long groupId : affected.stream().map(GroupMember::getGroupId).distinct().toList()) {
            resortGroupWithDisabledAtTail(groupId, now);
            routeEngine.resetPolling(groupId);
        }
        return affected.size();
    }

    /** 分组内重排：启用成员保持原相对顺序排前，停用成员保持原相对顺序排后，sortOrder 重写为 0..n-1。 */
    private void resortGroupWithDisabledAtTail(Long groupId, LocalDateTime now) {
        List<GroupMember> members = memberMapper.selectList(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getGroupId, groupId)
                .orderByAsc(GroupMember::getSortOrder));
        List<GroupMember> ordered = new ArrayList<>(members.size());
        members.stream().filter(m -> m.getEnabled() != null && m.getEnabled() == 1).forEach(ordered::add);
        members.stream().filter(m -> m.getEnabled() == null || m.getEnabled() != 1).forEach(ordered::add);
        for (int i = 0; i < ordered.size(); i++) {
            GroupMember m = ordered.get(i);
            if (m.getSortOrder() == null || m.getSortOrder() != i) {
                m.setSortOrder(i);
                m.setUpdatedAt(now);
                memberMapper.updateById(m);
            }
        }
    }
}
