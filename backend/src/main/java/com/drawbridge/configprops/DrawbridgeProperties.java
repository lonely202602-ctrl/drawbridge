package com.drawbridge.configprops;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * drawbridge 配置项（yml 兜底值；运营参数走 system_config 热更新，见 ConfigKeys）。
 */
@ConfigurationProperties(prefix = "drawbridge")
public record DrawbridgeProperties(
        String aesSecret,
        String adminSecret) {
}
