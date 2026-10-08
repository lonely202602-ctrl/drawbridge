package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型分组 = 业务方调用的 model 名。
 * strategy：POLLING 轮询 / RANDOM 随机 / FAILOVER 故障转移 / WEIGHTED 加权分配。
 */
@Data
@TableName("model_group")
public class ModelGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String groupCode;

    private String displayName;

    private String strategy;

    /** 我方售价/张（业务方按此扣额度） */
    private BigDecimal bizPrice;

    private Integer enabled;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
