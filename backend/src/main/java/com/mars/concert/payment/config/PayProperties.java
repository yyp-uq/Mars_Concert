package com.mars.concert.payment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 模拟支付配置：mars.pay.mock.base-url */
@Data
@Component
@ConfigurationProperties(prefix = "mars.pay.mock")
public class PayProperties {
    /** 收银台/回调基础地址，如 http://localhost:8080 */
    private String baseUrl = "http://localhost:8080";
}
