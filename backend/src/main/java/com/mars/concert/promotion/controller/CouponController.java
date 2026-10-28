package com.mars.concert.promotion.controller;

import com.mars.concert.common.annotation.OperationLog;
import com.mars.concert.common.result.Result;
import com.mars.concert.common.web.CurrentUser;
import com.mars.concert.promotion.model.vo.UserCouponVO;
import com.mars.concert.promotion.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "营销-优惠券")
@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @Operation(summary = "领券")
    @OperationLog(module = "营销", action = "领取优惠券", bizIdSpel = "#tplId")
    @PostMapping("/{tplId}/receive")
    public Result<Void> receive(@CurrentUser Long userId, @PathVariable Long tplId) {
        couponService.receive(userId, tplId);
        return Result.success();
    }

    @Operation(summary = "我的券")
    @GetMapping("/mine")
    public Result<List<UserCouponVO>> mine(@CurrentUser Long userId,
                                           @RequestParam(required = false) String status) {
        return Result.success(couponService.myCoupons(userId, status));
    }
}
