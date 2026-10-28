package com.mars.concert.payment.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付单信息（收银台/轮询查询用） */
@Data
public class PaymentVO {
    private String paymentNo;
    private String orderNo;
    private String channel;
    private BigDecimal amount;
    private String status;
    private LocalDateTime paidTime;
}
