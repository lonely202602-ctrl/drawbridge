package com.drawbridge.health;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.mapper.ChannelMapper;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 渠道运维状态机：自动停用（余额耗尽/Key 失效）与恢复。
 * 停用持久化到 DB + 刷新配置快照（转发链路立刻剔除）；恢复由管理端一键触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelOpsService {

    public static final int STATUS_MANUAL_DISABLED = 0;
    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_BALANCE_DISABLED = 4;

    private final ChannelMapper channelMapper;
    private final ChannelConfigService configService;

    /** 余额耗尽 / Key 失效：自动停用并告警。 */
    public void autoDisable(Long channelId, String reason) {
        channelMapper.update(null, Wrappers.<Channel>lambdaUpdate()
                .eq(Channel::getId, channelId)
                .set(Channel::getStatus, STATUS_BALANCE_DISABLED)
                .set(Channel::getStatusReason, reason)
                .set(Channel::getStatusTime, LocalDateTime.now()));
        configService.refresh();
        log.error("[drawbridge][告警] 渠道已自动停用: channelId={}, 原因={} —— 请充值后到管理端一键恢复",
                channelId, reason);
    }

    /** 管理端手动启用：清停用状态恢复调度。 */
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
        configService.refresh();
        log.info("渠道已手动停用: channelId={}, reason={}", channelId, reason);
    }
}
