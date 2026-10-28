package com.mars.concert.promotion.controller;

import com.mars.concert.common.annotation.OperationLog;
import com.mars.concert.common.result.Result;
import com.mars.concert.common.web.CurrentUser;
import com.mars.concert.promotion.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "营销-秒杀")
@RestController
@RequestMapping("/api/v1/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    @Operation(summary = "执行秒杀（立即返回排队凭证）")
    @OperationLog(module = "营销", action = "秒杀抢购", bizIdSpel = "#actId")
    @PostMapping("/{actId}/execute")
    public Result<String> execute(@CurrentUser Long userId, @PathVariable Long actId) {
        return Result.success(seckillService.execute(userId, actId));
    }

    @Operation(summary = "轮询秒杀结果")
    @GetMapping("/result")
    public Result<String> result(@CurrentUser Long userId, @RequestParam String ticket) {
        return Result.success(seckillService.result(ticket));
    }
}
