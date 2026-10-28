package com.mars.order.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.PrintWriter;

/**
 * Sentinel 限流/熔断兜底：被 BlockException 拦截时返回与全站一致的 Result JSON（HTTP 429），
 * 而不是 Sentinel 默认的纯文本 "Blocked by Sentinel"。
 */
@Configuration
public class SentinelBlockConfig {

    @Bean
    public BlockExceptionHandler blockExceptionHandler() {
        return new BlockExceptionHandler() {
            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response,
                               String resourceName, BlockException e) throws Exception {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                PrintWriter writer = response.getWriter();
                writer.write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\"}");
                writer.flush();
            }
        };
    }
}
