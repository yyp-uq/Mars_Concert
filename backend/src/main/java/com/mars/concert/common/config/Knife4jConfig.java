package com.mars.concert.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j 文档元信息。
 * 启动后访问 http://localhost:8080/doc.html 即可看到所有接口并在线调试。
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI marsOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("火星演唱会平台 API")
                .version("1.0.0")
                .description("Mars Concert Platform API Documentation")
                .contact(new Contact().name("mars")));
    }
}
