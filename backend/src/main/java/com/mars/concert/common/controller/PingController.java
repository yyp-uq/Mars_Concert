package com.mars.concert.common.controller;

import com.mars.concert.common.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PingController {

    // @Value 从 application.yml 读取配置，演示配置注入
    @Value("${spring.application.name}")
    private String appName;

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        return Result.success(Map.of(
                "app", appName,
                "time", System.currentTimeMillis()
        ));
    }
}
