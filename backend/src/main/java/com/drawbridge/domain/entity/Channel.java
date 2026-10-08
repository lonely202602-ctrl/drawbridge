package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 上游渠道：一家上游中转站的一个接入配置。
 * protocol 取值见 ProtocolFamily；api_key 加密存储。
 * status：1 正常 / 2 熔断中 / 3 半开探测 / 4 余额耗尽自动停用 / 0 手动停用。
 */
@Data
@TableName("channel")
public class Channel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String protocol;

    private String baseUrl;

    private String apiKeyEnc;

    /** 路径覆盖 JSON：{"submitPath":"/images/generations","queryPath":"/images/jobs/{id}"} */
    private String pathOverrides;

    /** 其它适配参数 JSON：如异步协议的字段指针 flavor/submitPath/queryPath/idField/statusField/urlField */
    private String extraConfig;

    /** 单渠道并发闸（默认 5，见 ConfigKeys） */
    private Integer maxConcurrency;

    /** 上游成本单价/张（对账用，可空） */
    private BigDecimal costPrice;

    private Integer enabled;

    private Integer status;

    private String statusReason;

    private LocalDateTime statusTime;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
