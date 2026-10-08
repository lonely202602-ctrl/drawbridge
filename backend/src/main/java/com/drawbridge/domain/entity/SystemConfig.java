package com.drawbridge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运营参数（键值表，进内存缓存热更新）。
 */
@Data
@TableName("system_config")
public class SystemConfig {

    @TableId(type = IdType.INPUT)
    private String configKey;

    private String configValue;

    private String description;

    private LocalDateTime updatedAt;
}
