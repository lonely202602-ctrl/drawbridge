package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务尝试流水：每打一个上游记一行（任务详情页的重试时间线）。
 * phase：SUBMIT 提交期 / POLL 轮询期；outcome：SUCCESS / FAILED。
 */
@Data
@TableName("task_attempt")
public class TaskAttempt {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Integer attemptNo;

    private Long channelId;

    private String upstreamModel;

    private String phase;

    private String outcome;

    private String errorKind;

    private Integer httpStatus;

    private String errorMsg;

    private Integer costMs;

    private LocalDateTime createdAt;
}
