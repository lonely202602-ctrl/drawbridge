package com.drawbridge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * drawbridge —— 图片/视频中转网关（生图先行）。
 * 端口 8180，Java 21 虚拟线程，Spring MVC 阻塞式写法。
 */
@EnableScheduling
@MapperScan("com.drawbridge.domain.mapper")
@ConfigurationPropertiesScan
@SpringBootApplication
public class DrawbridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(DrawbridgeApplication.class, args);
    }
}
