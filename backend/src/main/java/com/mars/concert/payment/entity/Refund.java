package com.mars.concert.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退款单（表 pay_refund） */
@Data
@TableName("pay_refund")
public class Refund {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String refundNo;
    private String orderNo;
    private String paymentNo;
    private BigDecimal amount;
    private String reason;
    /** APPLY/APPROVED/SUCCESS/REJECT */
    private String status;
    private String channelRefundNo;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
