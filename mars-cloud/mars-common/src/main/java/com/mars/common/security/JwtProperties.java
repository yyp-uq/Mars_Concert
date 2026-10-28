package com.mars.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mars.jwt")
public class JwtProperties {
    private String secret;
    private Long accessTtlSeconds;
    private Long refreshTtlSeconds;
}
