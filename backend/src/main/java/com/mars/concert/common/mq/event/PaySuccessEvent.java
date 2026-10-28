package com.mars.concert.common.mq.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 支付成功事件：支付回调事务提交后发到 MQ，扇出给出票/积分/通知消费者。
 * 必须有无参构造，供 Jackson 反序列化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaySuccessEvent {
    private String orderNo;
    private Long userId;
    private String paymentNo;
    /** 金额以字符串传递，避免浮点精度问题 */
    private String amount;
}
