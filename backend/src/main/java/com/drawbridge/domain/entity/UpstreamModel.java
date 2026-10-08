package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道上游模型清单（同步 /v1/models 入库或人工录入，作为分组成员的可选池）。
 */
@Data
@TableName("upstream_model")
public class UpstreamModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long channelId;

    /** 上游真实模型名，如 gpt-image-2.5 / openai/gpt-image-2-5 */
    private String modelCode;

    /** 1 同步拉取 / 2 人工录入 */
    private Integer source;

    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
