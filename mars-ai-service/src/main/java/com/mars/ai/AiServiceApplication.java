package com.mars.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 智能客服服务入口（端口 9009）。
 * 不使用 Spring Cloud，Nacos 注册由 nacos/NacosRegistration 手工完成。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
