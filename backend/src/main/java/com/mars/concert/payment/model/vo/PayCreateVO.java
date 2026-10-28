package com.mars.concert.payment.model.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 发起支付返回：收银台信息 */
@Data
public class PayCreateVO {
    private String paymentNo;
    /** 模拟收银台URL */
    private String payUrl;
    private BigDecimal amount;
}
