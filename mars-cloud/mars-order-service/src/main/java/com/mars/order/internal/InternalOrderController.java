package com.mars.order.internal;

import com.mars.common.result.Result;
import com.mars.order.model.vo.AiOrderBriefVO;
import com.mars.order.model.vo.AiOrderDetailVO;
import com.mars.order.model.vo.AiRefundResultVO;
import com.mars.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 第9卷：AI 客服内部订单接口。
 * /internal/** 不在网关路由中，仅允许内网直连；userId 由 AI 服务从登录态透传，
 * 所有查询/退款都强制按 userId 过滤，防止越权。
 */
@RestController
@RequestMapping("/internal/order")
@RequiredArgsConstructor
public class InternalOrderController {

    private final OrderService orderService;

    /** 我的订单列表（最多20条） */
    @GetMapping("/list")
    public Result<List<AiOrderBriefVO>> list(@RequestParam("userId") Long userId,
                                             @RequestParam(value = "status", required = false) String status) {
        return Result.success(orderService.listAiBrief(userId, status));
    }

    /** 订单详情（含票档明细与座位号） */
    @GetMapping("/detail")
    public Result<AiOrderDetailVO> detail(@RequestParam("userId") Long userId,
                                          @RequestParam("orderNo") String orderNo) {
        return Result.success(orderService.detailForAi(userId, orderNo));
    }

    /** 发起退款申请：校验归属与状态后，走状态机迁移到 REFUNDING */
    @PostMapping("/refund-apply")
    public Result<AiRefundResultVO> refundApply(@RequestParam("userId") Long userId,
                                                @RequestParam("orderNo") String orderNo) {
        return Result.success(orderService.applyRefundForAi(userId, orderNo));
    }
}
