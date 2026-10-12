package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号级批量关停批次：余额耗尽等信号触发同账号全部渠道关停时记录，
 * 充值后管理端一键整批恢复（渠道 + 联动停用的分组成员）。
 */
@Data
@TableName("disable_batch")
public class DisableBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String accountKey;

    private String reason;

    /** 本次关停的渠道 id JSON 数组 */
    private String channelIds;

    /** 本次联动停用的分组成员 id JSON 数组 */
    private String memberIds;

    /** 非空 = 已整批恢复 */
    private LocalDateTime restoredAt;

    private LocalDateTime createdAt;
}
