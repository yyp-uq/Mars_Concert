package com.mars.concert.payment.service;

import com.mars.concert.payment.model.dto.MockCallbackDTO;
import com.mars.concert.payment.model.vo.PayCreateVO;
import com.mars.concert.payment.model.vo.PaymentVO;

public interface PaymentService {

    /** 发起支付：生成 INIT 支付单，返回模拟收银台URL */
    PayCreateVO createPayment(Long userId, String orderNo, String channel);

    /** 处理模拟渠道回调：验签 → 幂等 → 支付单SUCCESS → 订单PAID → 本地消息 → afterCommit 发MQ */
    String handleMockCallback(MockCallbackDTO dto);

    /** 按订单号查询支付状态（前端轮询） */
    PaymentVO queryStatus(Long userId, String orderNo);

    /** 按支付单号查信息（收银台展示金额） */
    PaymentVO getInfo(String paymentNo);

    /** 申请退款：走状态机 REFUNDING，模拟渠道同步退款成功 REFUNDED + 票失效 + 库存回补 */
    void applyRefund(Long userId, String orderNo, String reason);
}
