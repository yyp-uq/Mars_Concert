package com.mars.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 客服自定义配置（application.yml 中 mars.ai 前缀）。
 */
@Data
@ConfigurationProperties(prefix = "mars.ai")
public class AiProperties {

    /** 依赖的内部微服务地址（AI 服务是独立工程，直接走 HTTP，不走注册中心） */
    private Services services = new Services();

    /** 退款二次确认 token 有效期（分钟） */
    private int refundConfirmTtlMinutes = 5;

    /** Nacos 注册参数 */
    private Nacos nacos = new Nacos();

    @Data
    public static class Services {
        /** 订单服务：/internal/order/** */
        private String orderUrl = "http://localhost:9003";
        /** 演出服务：/internal/show/** */
        private String showUrl = "http://localhost:9002";
    }

    @Data
    public static class Nacos {
        private String serverAddr = "localhost:8848";
        private String group = "MARS_GROUP";
        private String serviceName = "mars-ai-service";
        private int port = 9009;
        /** 关闭注册可避免本地没有 Nacos 时反复重试 */
        private boolean enabled = true;
    }
}
