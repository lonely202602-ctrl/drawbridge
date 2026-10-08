package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 生成任务：网关的真相源（DB 当队列，重启不丢）。
 * status：SUBMITTED 待执行 / RUNNING 执行中（含轮询期）/ SUCCEEDED / FAILED。
 * next_poll_time 双语义：SUBMITTED=可提交时间；RUNNING=下次轮询时间（推进即租约）。
 */
@Data
@TableName("generate_task")
public class GenerateTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private Long appId;

    private Long tokenId;

    private String groupCode;

    private String status;

    private Integer imageCount;

    /** 业务方原始请求体（存档，超上限截断） */
    private String bizRequest;

    /** 我方封装后发上游的请求体（受理渠道那一次，存档截断） */
    private String upstreamRequest;

    /** 结果 JSON：{"urls":[...],"inline":"b64_json"|null,"note":"..."}（截断存档） */
    private String result;

    private String errorCode;

    private String errorMsg;

    /** 最终受理渠道 */
    private Long channelId;

    private String upstreamTaskId;

    private Integer attemptCount;

    private Integer pollCount;

    private LocalDateTime nextPollTime;

    /** 生命周期截止：超时判 FAILED(TIMEOUT) */
    private LocalDateTime expiresAt;

    private BigDecimal freezeAmount;

    private BigDecimal settleAmount;

    private String clientRequestId;

    private LocalDateTime finishTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
