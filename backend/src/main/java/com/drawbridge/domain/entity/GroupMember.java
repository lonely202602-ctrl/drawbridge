package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分组成员：分组 × 渠道 × 上游模型 的挂载条目。
 * FAILOVER/POLLING 按 sortOrder 升序；WEIGHTED 按 weight 分配。
 */
@Data
@TableName("group_member")
public class GroupMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;

    private Long channelId;

    private String upstreamModel;

    private Integer weight;

    private Integer sortOrder;

    private Integer enabled;

    /** 停用出处：cascade=渠道/账号停用联动（渠道启用会恢复）；admin=管理员手动停用（不自动恢复）；NULL=在用或历史数据 */
    private String disableSource;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
