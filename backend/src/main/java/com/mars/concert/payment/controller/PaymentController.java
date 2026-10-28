package com.mars.concert.payment.controller;

import com.mars.concert.common.result.Result;
import com.mars.concert.common.web.CurrentUser;
import com.mars.concert.payment.model.dto.MockCallbackDTO;
import com.mars.concert.payment.model.vo.PayCreateVO;
import com.mars.concert.payment.model.vo.PaymentVO;
import com.mars.concert.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "交易-支付")
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "发起支付")
    @PostMapping("/api/v1/payments/create")
    public Result<PayCreateVO> create(@CurrentUser Long uid,
                                      @RequestParam String orderNo,
                                      @RequestParam(defaultValue = "MOCK") String channel) {
        return Result.success(paymentService.createPayment(uid, orderNo, channel));
    }

    @Operation(summary = "按订单号查支付状态(轮询)")
    @GetMapping("/api/v1/payments/{orderNo}/status")
    public Result<PaymentVO> status(@CurrentUser Long uid, @PathVariable String orderNo) {
        return Result.success(paymentService.queryStatus(uid, orderNo));
    }

    @Operation(summary = "按支付单号查信息(收银台)")
    @GetMapping("/api/v1/payments/{paymentNo}/info")
    public Result<PaymentVO> info(@PathVariable String paymentNo) {
        return Result.success(paymentService.getInfo(paymentNo));
    }

    @Operation(summary = "申请退款")
    @PostMapping("/api/v1/payments/{orderNo}/refund")
    public Result<Void> refund(@CurrentUser Long uid, @PathVariable String orderNo,
                               @RequestParam(required = false) String reason) {
        paymentService.applyRefund(uid, orderNo, reason);
        return Result.success();
    }

    @Operation(summary = "模拟渠道支付回调(渠道服务器调用，无用户token)")
    @PostMapping("/api/v1/pay/callback/mock")
    public Result<String> mockCallback(@RequestBody MockCallbackDTO dto) {
        return Result.success(paymentService.handleMockCallback(dto));
    }
}
