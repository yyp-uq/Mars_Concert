package com.mars.concert.common.mq;

/**
 * MQ 拓扑常量：交换机 / 路由键 / 业务队列 / 死信队列。
 * 一个 routingKey(pay.success) 被 3 个业务队列绑定 → 发布订阅扇出（出票/积分/通知各收一份）。
 */
public interface MqConstants {
    String PAY_EXCHANGE = "pay.exchange";
    String RK_PAY_SUCCESS = "pay.success";

    String Q_TICKET_CREATE = "q.ticket.create";
    String Q_POINT_ADD = "q.point.add";
    String Q_NOTIFY_SEND = "q.notify.send";

    // 死信
    String DLX_EXCHANGE = "dlq.exchange";
    String Q_TICKET_CREATE_DLQ = "q.ticket.create.dlq";
    String Q_POINT_ADD_DLQ = "q.point.add.dlq";
    String Q_NOTIFY_SEND_DLQ = "q.notify.send.dlq";

    // ---------- 第6卷：秒杀（Redis 预减后 MQ 异步下单削峰） ----------
    String SECKILL_EXCHANGE = "seckill.exchange";
    String RK_SECKILL_ORDER = "seckill.order";
    String Q_SECKILL_ORDER = "q.seckill.order";
}
