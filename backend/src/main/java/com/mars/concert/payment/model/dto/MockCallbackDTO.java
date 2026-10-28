package com.mars.concert.payment.model.dto;

import lombok.Data;

/** 模拟渠道回调请求体 */
@Data
public class MockCallbackDTO {
    private String paymentNo;
    private String channelTradeNo;
    private String sign;
    private String paidTime;
}
