package com.drawbridge.health;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.common.JsonUtil;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.DisableBatch;
import com.drawbridge.domain.entity.GroupMember;
import com.drawbridge.domain.mapper.ChannelMapper;
import com.drawbridge.domain.mapper.DisableBatchMapper;
import com.drawbridge.domain.mapper.GroupMemberMapper;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.route.RouteEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 渠道运维状态机：自动停用（余额耗尽/Key 失效）与恢复。
 * 停用持久化到 DB + 刷新配置快照（转发链路立刻剔除）+ 联动停用分组成员；恢复由管理端一键触发。
 *
 * v0.4 变化：
 * - autoDisableAccount：精确余额信号触发同账号（同 base_url+apiKey）渠道批量停用，
 *   关掉的渠道与联动成员记入 disable_batch 批次，充值后 restoreBatch 一键整批恢复
 *   （修掉"启用渠道不恢复联动成员"的旧缺陷：单渠道 enable 现在会一并恢复被联动停用的成员）。
 *
 * v0.4.1 变化：
 * - 自动停用同时落 enabled=0（原先只改 status，并发 402 会重复建批次、批次查询筛 enabled=1 也漏判）；
 *   展示层以 status=4 优先判"余额停用"，语义不受影响。
 * - 成员停用记出处（disable_source：cascade=联动 / admin=手动）：渠道启用只复活联动停用的成员，
 *   管理员手动停的不再被顺手拉起。
 * - 单渠道启用会从待恢复批次里摘除该渠道（成员一并摘除），批内清空即关闭批次，
 *   避免"整批恢复"按钮滞留、误点复活管理员后来手动停用的东西。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelOpsService {

    public static final int STATUS_MANUAL_DISABLED = 0;
    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_BALANCE_DISABLED = 4;

    /** 成员停用出处（group_member.disable_source）：联动停用（渠道/账号停用波及）。 */
    public static final String MEMBER_SOURCE_CASCADE = "cascade";
    /** 成员停用出处：管理员手动停用——渠道启用/批次恢复都不会复活它。 */
    public static final String MEMBER_SOURCE_ADMIN = "admin";

    private final ChannelMapper channelMapper;
    private final GroupMemberMapper memberMapper;
    private final DisableBatchMapper disableBatchMapper;
    private final ChannelConfigService configService;
    private final RouteEngine routeEngine;

    /** 余额耗尽 / Key 失效：自动停用并告警（单渠道）。enabled 一并落 0，展示层按 status=4 判"余额停用"。 */
    public void autoDisable(Long channelId, String reason) {
        channelMapper.update(null, Wrappers.<Channel>lambdaUpdate()
                .eq(Channel::getId, channelId)
                .set(Channel::getStatus, STATUS_BALANCE_DISABLED)
                .set(Channel::getStatusReason, reason)
                .set(Channel::getStatusTime, LocalDateTime.now())
                .set(Channel::getEnabled, 0));
        List<Long> members = cascadeDisableMembers(channelId);
        configService.refresh();
        log.error("[drawbridge][告警] 渠道已自动停用: channelId={}, 原因={} —— 请充值后到管理端一键恢复",
                channelId, reason);
        if (!members.isEmpty()) {
            log.warn("渠道停用联动: channelId={} 已停用其分组成员 {} 个并滑至各分组队尾", channelId, members.size());
        }
    }

    /**
     * 账号级批量停用（精确余额信号专用）：同账号（同 base_url+apiKey）全部启用渠道一起停，
     * 记入 disable_batch 批次供整批恢复。账号键缺失（未迁移的存量渠道）回退单渠道停用。
     * 渠道 enabled 一并落 0：并发多个任务同时收到余额信号时，第二个起查询不到启用渠道直接返回，
     * 不会重复建批次（此前只改 status，同账号并发 402 每单各建一批）。
     */
    public void autoDisableAccount(String accountKey, String reason) {
        if (accountKey == null || accountKey.isBlank()) {
            log.warn("账号级停用缺少账号键，跳过（渠道未迁移？）");
            return;
        }
        List<Channel> channels = channelMapper.selectList(Wrappers.<Channel>lambdaQuery()
                .eq(Channel::getAccountKey, accountKey)
                .eq(Channel::getEnabled, 1));
        if (channels.isEmpty()) {
            log.warn("账号级停用未找到启用渠道（已停用过则幂等跳过）: accountKey={}", AccountRegistry.shortKey(accountKey));
            return;
        }
        List<Long> channelIds = new ArrayList<>();
        List<Long> memberIds = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Channel c : channels) {
            // eq(enabled,1) 是行级抢占闸：并发两个任务同时触发账号停用时，
            // 每个渠道行只会被其中一个事务成功更新（另一个 rows=0）——渠道不会重复进批
            int rows = channelMapper.update(null, Wrappers.<Channel>lambdaUpdate()
                    .eq(Channel::getId, c.getId())
                    .eq(Channel::getEnabled, 1)
                    .set(Channel::getStatus, STATUS_BALANCE_DISABLED)
                    .set(Channel::getStatusReason, reason)
                    .set(Channel::getStatusTime, now)
                    .set(Channel::getEnabled, 0));
            if (rows != 1) {
                continue;
            }
            channelIds.add(c.getId());
            memberIds.addAll(cascadeDisableMembers(c.getId()));
        }
        if (channelIds.isEmpty()) {
            log.info("账号级停用被并发抢先，无需建批: accountKey={}", AccountRegistry.shortKey(accountKey));
            return;
        }
        DisableBatch batch = new DisableBatch();
        batch.setAccountKey(accountKey);
        batch.setReason(reason);
        batch.setChannelIds(JsonUtil.toJson(channelIds));
        batch.setMemberIds(JsonUtil.toJson(memberIds));
        batch.setCreatedAt(now);
        disableBatchMapper.insert(batch);
        configService.refresh();
        log.error("[drawbridge][告警] 上游账号已批量停用: accountKey={}, 渠道={} 个, 联动成员={} 个, 原因={} —— 请充值后到管理端整批恢复",
                AccountRegistry.shortKey(accountKey), channelIds.size(), memberIds.size(), reason);
    }

    /**
     * 管理端手动启用：清停用状态恢复调度，并恢复被联动停用的分组成员（重新排在启用成员之后）；
     * 同时把该渠道从账号关停批次里摘除（批内清空即关批），"整批恢复"按钮不滞留。
     */
    public void enable(Long channelId) {
        channelMapper.update(null, Wrappers.<Channel>lambdaUpdate()
                .eq(Channel::getId, channelId)
                .set(Channel::getStatus, STATUS_NORMAL)
                .set(Channel::getStatusReason, null)
                .set(Channel::getStatusTime, LocalDateTime.now())
                .set(Channel::getEnabled, 1));
        List<Long> restored = restoreMembers(channelId);
        Channel channel = channelMapper.selectById(channelId);
        if (channel != null) {
            settleBatchesForEnabledChannel(channel);
        }
        configService.refresh();
        log.info("渠道已启用: channelId={}, 恢复联动成员 {} 个", channelId, restored.size());
    }

    /** 整批恢复（关停批次）：恢复该批次全部渠道与联动成员。已恢复过的批次忽略。 */
    public void restoreBatch(Long batchId) {
        DisableBatch batch = disableBatchMapper.selectById(batchId);
        if (batch == null) {
            log.warn("恢复批次不存在: batchId={}", batchId);
            return;
        }
        if (batch.getRestoredAt() != null) {
            log.info("批次已恢复过，忽略: batchId={}", batchId);
            return;
        }
        List<Long> channelIds = readIds(batch.getChannelIds());
        List<Long> memberIds = readIds(batch.getMemberIds());
        LocalDateTime now = LocalDateTime.now();
        for (Long channelId : channelIds) {
            // 只恢复仍处于"余额停用"的渠道：批次期间管理员手动停的（status=0）不拉起
            channelMapper.update(null, Wrappers.<Channel>lambdaUpdate()
                    .eq(Channel::getId, channelId)
                    .eq(Channel::getStatus, STATUS_BALANCE_DISABLED)
                    .set(Channel::getStatus, STATUS_NORMAL)
                    .set(Channel::getStatusReason, null)
                    .set(Channel::getStatusTime, now)
                    .set(Channel::getEnabled, 1));
        }
        for (Long memberId : memberIds) {
            // 只恢复仍是联动停用的成员：批次期间管理员手动停的（source=admin）不拉起
            memberMapper.update(null, Wrappers.<GroupMember>lambdaUpdate()
                    .eq(GroupMember::getId, memberId)
                    .eq(GroupMember::getEnabled, 0)
                    .eq(GroupMember::getDisableSource, MEMBER_SOURCE_CASCADE)
                    .set(GroupMember::getEnabled, 1)
                    .set(GroupMember::getDisableSource, null)
                    .set(GroupMember::getUpdatedAt, now));
        }
        // 受影响分组重排（启用在前保持原序，停用在后）并清轮询记忆
        memberMapper.selectList(Wrappers.<GroupMember>lambdaQuery()
                        .in(GroupMember::getId, memberIds.isEmpty() ? List.of(-1L) : memberIds))
                .stream().map(GroupMember::getGroupId).distinct()
                .forEach(groupId -> {
                    resortGroupWithDisabledAtTail(groupId, now);
                    routeEngine.resetPolling(groupId);
                });
        disableBatchMapper.update(null, Wrappers.<DisableBatch>lambdaUpdate()
                .eq(DisableBatch::getId, batchId)
                .set(DisableBatch::getRestoredAt, now));
        configService.refresh();
        log.info("关停批次已整批恢复: batchId={}, 渠道={} 个, 成员={} 个",
                batchId, channelIds.size(), memberIds.size());
    }

    /** 最近未恢复的关停批次（渠道启用横幅提示用）。 */
    public List<DisableBatch> pendingBatches(String accountKey) {
        return disableBatchMapper.selectList(Wrappers.<DisableBatch>lambdaQuery()
                .eq(DisableBatch::getAccountKey, accountKey)
                .isNull(DisableBatch::getRestoredAt)
                .orderByDesc(DisableBatch::getId));
    }

    public void disable(Long channelId, String reason) {
        channelMapper.update(null, Wrappers.<Channel>lambdaUpdate()
                .eq(Channel::getId, channelId)
                .set(Channel::getStatus, STATUS_MANUAL_DISABLED)
                .set(Channel::getStatusReason, reason)
                .set(Channel::getStatusTime, LocalDateTime.now())
                .set(Channel::getEnabled, 0));
        List<Long> members = cascadeDisableMembers(channelId);
        configService.refresh();
        log.info("渠道已手动停用: channelId={}, reason={}, 联动停用成员 {} 个", channelId, reason, members.size());
    }

    /**
     * 恢复引用该渠道且被联动停用的成员（disable_source=cascade），管理员手动停用的不碰。
     * 分组重排由 resort 完成。
     */
    private List<Long> restoreMembers(Long channelId) {
        List<GroupMember> affected = memberMapper.selectList(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getChannelId, channelId)
                .eq(GroupMember::getEnabled, 0)
                .eq(GroupMember::getDisableSource, MEMBER_SOURCE_CASCADE));
        if (affected.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        for (GroupMember m : affected) {
            // updateById 会忽略 null 字段（出处清不掉），必须 lambdaUpdate 显式 set null
            memberMapper.update(null, Wrappers.<GroupMember>lambdaUpdate()
                    .eq(GroupMember::getId, m.getId())
                    .set(GroupMember::getEnabled, 1)
                    .set(GroupMember::getDisableSource, null)
                    .set(GroupMember::getUpdatedAt, now));
        }
        for (Long groupId : affected.stream().map(GroupMember::getGroupId).distinct().toList()) {
            resortGroupWithDisabledAtTail(groupId, now);
            routeEngine.resetPolling(groupId);
        }
        return affected.stream().map(GroupMember::getId).toList();
    }

    /**
     * 渠道停用联动：引用该渠道的分组成员全部停用，并滑到所在分组队尾。
     * 排序重写为"启用成员在前（原相对顺序不变），停用成员在后（原相对顺序不变）"，
     * 多次停用/多个停用成员都保持稳定，不会来回跳动。
     *
     * @return 本次联动停用的成员 id
     */
    private List<Long> cascadeDisableMembers(Long channelId) {
        List<GroupMember> affected = memberMapper.selectList(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getChannelId, channelId)
                .eq(GroupMember::getEnabled, 1));
        if (affected.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        for (GroupMember m : affected) {
            m.setEnabled(0);
            m.setDisableSource(MEMBER_SOURCE_CASCADE);
            m.setUpdatedAt(now);
            memberMapper.updateById(m);
        }
        for (Long groupId : affected.stream().map(GroupMember::getGroupId).distinct().toList()) {
            resortGroupWithDisabledAtTail(groupId, now);
            routeEngine.resetPolling(groupId);
        }
        return affected.stream().map(GroupMember::getId).toList();
    }

    /**
     * 单渠道手动启用时收敛账号关停批次：把该渠道（及其联动成员 id）从待恢复批次里摘除，
     * 批内渠道清空即关闭批次。防止"整批恢复"按钮滞留——之后误点会把管理员后来手动停用的
     * 渠道/成员一并复活。管理员手动停的成员不在批次成员清单里，不受摘除影响。
     */
    private void settleBatchesForEnabledChannel(Channel channel) {
        if (channel.getAccountKey() == null || channel.getAccountKey().isBlank()) {
            return;
        }
        List<DisableBatch> pending = disableBatchMapper.selectList(Wrappers.<DisableBatch>lambdaQuery()
                .eq(DisableBatch::getAccountKey, channel.getAccountKey())
                .isNull(DisableBatch::getRestoredAt));
        for (DisableBatch batch : pending) {
            List<Long> channelIds = readIds(batch.getChannelIds());
            if (!channelIds.contains(channel.getId())) {
                continue;
            }
            Set<Long> channelMembers = memberMapper.selectList(Wrappers.<GroupMember>lambdaQuery()
                            .eq(GroupMember::getChannelId, channel.getId()))
                    .stream().map(GroupMember::getId).collect(Collectors.toSet());
            List<Long> remainChannels = channelIds.stream()
                    .filter(id -> !id.equals(channel.getId())).toList();
            List<Long> remainMembers = readIds(batch.getMemberIds()).stream()
                    .filter(id -> !channelMembers.contains(id)).toList();
            LocalDateTime now = LocalDateTime.now();
            if (remainChannels.isEmpty()) {
                disableBatchMapper.update(null, Wrappers.<DisableBatch>lambdaUpdate()
                        .eq(DisableBatch::getId, batch.getId())
                        .set(DisableBatch::getRestoredAt, now));
            } else {
                disableBatchMapper.update(null, Wrappers.<DisableBatch>lambdaUpdate()
                        .eq(DisableBatch::getId, batch.getId())
                        .set(DisableBatch::getChannelIds, JsonUtil.toJson(remainChannels))
                        .set(DisableBatch::getMemberIds, JsonUtil.toJson(remainMembers)));
            }
            log.info("渠道手动启用已从关停批次摘除: batchId={}, channelId={}, 批内剩余渠道={} 个",
                    batch.getId(), channel.getId(), remainChannels.size());
        }
    }

    private static List<Long> readIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JsonUtil.readTree(json).valueStream().map(n -> n.asLong()).toList();
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
