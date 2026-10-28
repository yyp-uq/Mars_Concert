package com.mars.concert.payment.controller;

import com.mars.concert.payment.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 极简模拟支付网关/收银台入口。
 * 本卷不引入 Thymeleaf：直接重定向到静态收银台页 static/mock/cashier.html，
 * 该静态页再调 /api/v1/payments/{paymentNo}/info 拿金额，由浏览器代发回调（学习演示用）。
 */
@Controller
@RequestMapping("/mock")
@RequiredArgsConstructor
public class MockGatewayController {

    private final PaymentMapper paymentMapper;

    @GetMapping("/cashier")
    public String cashier(@RequestParam String paymentNo) {
        return "redirect:/mock/cashier.html?paymentNo=" + paymentNo;
    }
}
