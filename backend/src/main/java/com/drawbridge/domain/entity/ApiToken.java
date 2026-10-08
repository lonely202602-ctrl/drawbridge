package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 业务方令牌：SK 只在创建时返回一次，库里只存 SHA-256 与前缀。
 * allowed_groups JSON 数组为空/NULL = 不限分组。
 */
@Data
@TableName("api_token")
public class ApiToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appId;

    private String name;

    private String skHash;

    private String skPrefix;

    private Integer status;

    private BigDecimal quotaTotal;

    private BigDecimal quotaUsed;

    private String allowedGroups;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
