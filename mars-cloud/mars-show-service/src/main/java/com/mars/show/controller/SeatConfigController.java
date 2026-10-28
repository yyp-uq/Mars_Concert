package com.mars.show.controller;

import com.mars.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nacos 动态刷新演示（第8卷 §5）：在 Nacos 控制台修改
 * mars-show-service-dev.yaml 里的 mars.seat.lock-ttl 并发布后，
 * 不重启服务，再次访问本接口立即看到新值。
 */
@Tag(name = "演出-动态配置演示")
@RestController
@RefreshScope
@RequestMapping("/api/v1/show")
public class SeatConfigController {

    @Value("${mars.seat.lock-ttl:300}")
    private int lockTtl;

    @Operation(summary = "读取座位锁TTL(Nacos动态刷新)")
    @GetMapping("/seat-config")
    public Result<Integer> ttl() {
        return Result.success(lockTtl);
    }
}
