package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用量流水：任务终态时落一行（计量与对账）。
 */
@Data
@TableName("usage_log")
public class UsageLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private Long appId;

    private Long tokenId;

    private String groupCode;

    private Long channelId;

    private Integer success;

    private Integer imageCount;

    /** 向业务方计费金额 */
    private BigDecimal bizAmount;

    /** 上游成本金额（对账用） */
    private BigDecimal costAmount;

    private String errorKind;

    private LocalDateTime createdAt;
}
