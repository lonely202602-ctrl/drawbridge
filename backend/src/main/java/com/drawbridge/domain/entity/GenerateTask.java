package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成任务：网关的真相源（DB 当队列，重启不丢）。
 * status：SUBMITTED 待执行 / RUNNING 执行中（含轮询期）/ SUCCEEDED / FAILED。
 * next_poll_time 双语义：SUBMITTED=可提交时间；RUNNING=下次轮询时间（推进即租约）。
 * 注：freeze_amount/settle_amount 为早期计价方案遗留列，纯网关模式不读写。
 */
@Data
@TableName("generate_task")
public class GenerateTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private Long tokenId;

    private String groupCode;

    private String status;

    private Integer imageCount;

    /** 业务方原始请求体（MEDIUMTEXT 完整存档；执行链路从它解析入参，不截断） */
    private String bizRequest;

    /** 我方封装后发上游的请求体（受理渠道那一次，存档截断） */
    private String upstreamRequest;

    /** 最后一次提交尝试命中的上游完整 URL（存档展示用） */
    private String upstreamUrl;

    /** 结果 JSON：{"urls":[...],"inline":"b64_json"|null,"note":"..."}（截断存档） */
    private String result;

    private String errorCode;

    private String errorMsg;

    /** 最终受理渠道 */
    private Long channelId;

    private String upstreamTaskId;

    private Integer attemptCount;

    private Integer pollCount;

    /** 提交链路回退重跑计数（回退退避与 5 次上限用它；受理成功清零。与轮询次数 poll_count 语义分离） */
    private Integer rerunCount;

    /** 轮询期连续 404 计数（任务丢失宽限判据：连续 N 次才终态，非 404 轮询即清零） */
    private Integer notFoundCount;

    /** 最近一次轮询上游的时间（轮询器领取即写，管理端任务详情展示轮询活性） */
    private LocalDateTime lastPollAt;

    private LocalDateTime nextPollTime;

    /** 生命周期截止：超时判 FAILED(TIMEOUT) */
    private LocalDateTime expiresAt;

    private String clientRequestId;

    private LocalDateTime finishTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
