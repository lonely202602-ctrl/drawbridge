package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务方应用：一个接入方一个 app，令牌挂在 app 下。
 */
@Data
@TableName("app")
public class App {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String contact;

    private Integer enabled;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
