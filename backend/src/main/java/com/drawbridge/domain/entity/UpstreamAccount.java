package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上游账号：同 base_url+apiKey 的渠道共享一个并发池。
 * account_key = sha256(归一化base_url|apiKey) 前 32 位；capacity 显式配置，0=暂停派发。
 * v0.4 起渠道上的 max_concurrency 列废弃，容量以本表为准。
 */
@Data
@TableName("upstream_account")
public class UpstreamAccount {

    @TableId
    private String accountKey;

    /** 该账号同时在跑任务数上限；0=暂停派发 */
    private Integer capacity;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
