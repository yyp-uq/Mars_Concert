package com.mars.concert.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付单（表 pay_payment） */
@Data
@TableName("pay_payment")
public class Payment {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 我方支付单号 */
    private String paymentNo;
    private String orderNo;
    private Long userId;
    /** MOCK/ALIPAY/WECHAT */
    private String channel;
    private BigDecimal amount;
    /** INIT/SUCCESS/FAIL/CLOSED */
    private String status;
    /** 渠道流水号(幂等关键) */
    private String channelTradeNo;
    private String callbackRaw;
    private LocalDateTime paidTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
