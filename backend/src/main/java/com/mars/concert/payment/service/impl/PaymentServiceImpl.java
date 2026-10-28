package com.mars.concert.payment.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.concert.common.enums.CommonErrorCode;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.common.mq.MqConstants;
import com.mars.concert.common.mq.ReliableMessageSender;
import com.mars.concert.common.mq.entity.LocalMessage;
import com.mars.concert.common.mq.event.PaySuccessEvent;
import com.mars.concert.common.utils.OrderNoGenerator;
import com.mars.concert.common.utils.SignUtil;
import com.mars.concert.order.entity.Orders;
import com.mars.concert.order.enums.OrderErrorCode;
import com.mars.concert.order.enums.OrderEvent;
import com.mars.concert.order.enums.OrderStatus;
import com.mars.concert.order.mapper.OrderMapper;
import com.mars.concert.order.service.OrderService;
import com.mars.concert.payment.config.PayProperties;
import com.mars.concert.payment.entity.Payment;
import com.mars.concert.payment.entity.Refund;
import com.mars.concert.payment.mapper.PaymentMapper;
import com.mars.concert.payment.mapper.RefundMapper;
import com.mars.concert.payment.model.dto.MockCallbackDTO;
import com.mars.concert.payment.model.vo.PayCreateVO;
import com.mars.concert.payment.model.vo.PaymentVO;
import com.mars.concert.payment.service.PaymentService;
import com.mars.concert.promotion.service.CouponService;
import com.mars.concert.show.inventory.service.SeatInventoryService;
import com.mars.concert.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    /** 模拟渠道密钥（真实场景仅存服务端/渠道，绝不下发前端） */
    private static final String MOCK_SECRET = "MARS_MOCK_SECRET";

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final SeatInventoryService seatInventoryService;
    private final TicketService ticketService;
    private final ReliableMessageSender reliableMessageSender;
    private final PayProperties payProps;
    private final ObjectMapper objectMapper;
    private final CouponService couponService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayCreateVO createPayment(Long userId, String orderNo, String channel) {
        Orders order = orderMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getOrderNo, orderNo).eq(Orders::getUserId, userId));
        if (order == null) throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
        if (!OrderStatus.WAIT_PAY.name().equals(order.getStatus())) {
            throw new BusinessException(OrderErrorCode.ORDER_STATUS_ILLEGAL);
        }

        Payment pay = new Payment();
        pay.setPaymentNo(OrderNoGenerator.payNo());
        pay.setOrderNo(orderNo);
        pay.setUserId(userId);
        pay.setChannel(channel == null ? "MOCK" : channel);
        pay.setAmount(order.getPayAmount());
        pay.setStatus("INIT");
        paymentMapper.insert(pay);

        // 支付成功时才发 MQ，此处只返回模拟收银台URL
        PayCreateVO vo = new PayCreateVO();
        vo.setPaymentNo(pay.getPaymentNo());
        vo.setAmount(order.getPayAmount());
        vo.setPayUrl(payProps.getBaseUrl() + "/mock/cashier?paymentNo=" + pay.getPaymentNo());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String handleMockCallback(MockCallbackDTO dto) {
        // ① 验签：服务端用同样规则重算签名比对
        String expect = SignUtil.sha256(dto.getPaymentNo() + dto.getChannelTradeNo() + MOCK_SECRET);
        if (!expect.equalsIgnoreCase(dto.getSign())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN.getCode(), "回调验签失败");
        }

        Payment pay = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getPaymentNo, dto.getPaymentNo()));
        if (pay == null) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);

        // ② 幂等：状态判断 + 渠道流水唯一。重复回调直接返回成功(渠道要求ACK success)
        if ("SUCCESS".equals(pay.getStatus())) {
            return pay.getOrderNo();
        }
        Payment dup = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getChannelTradeNo, dto.getChannelTradeNo()));
        if (dup != null) return pay.getOrderNo();

        // ③ 更新支付单
        pay.setStatus("SUCCESS");
        pay.setChannelTradeNo(dto.getChannelTradeNo());
        pay.setCallbackRaw(toJson(dto));
        pay.setPaidTime(LocalDateTime.now());
        paymentMapper.updateById(pay);

        // ④ 订单状态机 WAIT_PAY → PAID
        Orders order = orderMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getOrderNo, pay.getOrderNo()));
        if (order == null) throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
        if (OrderStatus.WAIT_PAY.name().equals(order.getStatus())) {
            orderService.transit(order.getId(), OrderEvent.PAY_SUCCESS, "MOCK");
        }

        // ⑤ 同事务写本地消息(PENDING)，事务提交后再发 MQ
        PaySuccessEvent event = new PaySuccessEvent(pay.getOrderNo(), order.getUserId(),
                pay.getPaymentNo(), pay.getAmount().toPlainString());
        LocalMessage m = reliableMessageSender.savePending(pay.getOrderNo(),
                MqConstants.PAY_EXCHANGE, MqConstants.RK_PAY_SUCCESS, event);
        Long msgId = m.getId();

        // ⑥ 事务提交后发送（保证库里有支付成功状态，消费者收到时一定能查到订单）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reliableMessageSender.send(msgId);
            }
        });
        return pay.getOrderNo();
    }

    @Override
    public PaymentVO queryStatus(Long userId, String orderNo) {
        Payment pay = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getOrderNo, orderNo).eq(Payment::getUserId, userId)
                .orderByDesc(Payment::getId).last("LIMIT 1"));
        if (pay == null) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        return toVO(pay);
    }

    @Override
    public PaymentVO getInfo(String paymentNo) {
        Payment pay = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getPaymentNo, paymentNo));
        if (pay == null) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        return toVO(pay);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyRefund(Long userId, String orderNo, String reason) {
        Orders order = orderMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getOrderNo, orderNo).eq(Orders::getUserId, userId));
        if (order == null) throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);

        // 仅 PAID/TICKETED 可申请（非法状态由状态机抛 ORDER_STATUS_ILLEGAL）
        orderService.transit(order.getId(), OrderEvent.APPLY_REFUND, String.valueOf(userId));

        Payment pay = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getOrderNo, orderNo).eq(Payment::getStatus, "SUCCESS")
                .orderByDesc(Payment::getId).last("LIMIT 1"));

        Refund r = new Refund();
        r.setRefundNo(OrderNoGenerator.refundNo());
        r.setOrderNo(orderNo);
        r.setPaymentNo(pay == null ? null : pay.getPaymentNo());
        r.setAmount(order.getPayAmount());
        r.setReason(reason);
        r.setStatus("APPLY");
        refundMapper.insert(r);

        // 学习期：自动审核通过并走模拟退款；真实需客服审核 + 渠道退款回调幂等
        doMockRefund(r, order);
    }

    private void doMockRefund(Refund r, Orders order) {
        r.setStatus("SUCCESS");
        r.setChannelRefundNo("MOCKRF" + System.currentTimeMillis());
        refundMapper.updateById(r);

        // 订单 REFUNDING → REFUNDED；票失效；库存回补
        orderService.transit(order.getId(), OrderEvent.REFUND_SUCCESS, "MOCK");
        ticketService.markRefunded(order.getId());
        seatInventoryService.releasePaid(order);
        // 第6卷：退款成功回退券（幂等）
        couponService.refundByOrderNo(order.getOrderNo());
        log.info("模拟退款成功 orderNo={} refundNo={}", order.getOrderNo(), r.getRefundNo());
    }

    private PaymentVO toVO(Payment pay) {
        PaymentVO vo = new PaymentVO();
        vo.setPaymentNo(pay.getPaymentNo());
        vo.setOrderNo(pay.getOrderNo());
        vo.setChannel(pay.getChannel());
        vo.setAmount(pay.getAmount());
        vo.setStatus(pay.getStatus());
        vo.setPaidTime(pay.getPaidTime());
        return vo;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
