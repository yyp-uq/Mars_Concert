package com.mars.concert.promotion.controller;

import com.mars.concert.common.annotation.OperationLog;
import com.mars.concert.common.result.Result;
import com.mars.concert.common.web.CurrentUser;
import com.mars.concert.promotion.service.PointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "营销-积分")
@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @Operation(summary = "每日签到")
    @OperationLog(module = "营销", action = "积分签到")
    @PostMapping("/sign")
    public Result<Void> sign(@CurrentUser Long userId) {
        pointService.sign(userId);
        return Result.success();
    }

    @Operation(summary = "我的积分余额")
    @GetMapping("/balance")
    public Result<Integer> balance(@CurrentUser Long userId) {
        return Result.success(pointService.balance(userId));
    }
}
