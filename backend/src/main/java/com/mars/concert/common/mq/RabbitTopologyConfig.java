package com.mars.concert.common.mq;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 用 Bean 声明交换机/队列/绑定。应用启动时若不存在则自动创建。
 * 每个业务队列都绑定一个死信交换机：消费失败 nack(requeue=false) 后消息进死信队列。
 * 另注册 returns 回调：交换机找不到队列时记录告警（mandatory=true）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitTopologyConfig {

    private final RabbitTemplate rabbitTemplate;

    /** return 回调：消息路由不到任何队列时告警（拓扑由本类声明，正常不会触发） */
    @PostConstruct
    public void configReturnsCallback() {
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "【MQ 退回告警】消息无法路由 exchange={} rk={} reply={} body={}",
                returned.getExchange(), returned.getRoutingKey(),
                returned.getReplyText(), returned.getMessage()));
    }

    @Bean
    public TopicExchange payExchange() {
        return ExchangeBuilder.topicExchange(MqConstants.PAY_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(MqConstants.DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue ticketQueue() {
        return QueueBuilder.durable(MqConstants.Q_TICKET_CREATE)
                .withArgument("x-dead-letter-exchange", MqConstants.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.Q_TICKET_CREATE_DLQ)
                .build();
    }

    @Bean
    public Queue pointQueue() {
        return QueueBuilder.durable(MqConstants.Q_POINT_ADD)
                .withArgument("x-dead-letter-exchange", MqConstants.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.Q_POINT_ADD_DLQ)
                .build();
    }

    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(MqConstants.Q_NOTIFY_SEND)
                .withArgument("x-dead-letter-exchange", MqConstants.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.Q_NOTIFY_SEND_DLQ)
                .build();
    }

    @Bean
    public Binding bindTicket() {
        return BindingBuilder.bind(ticketQueue()).to(payExchange()).with(MqConstants.RK_PAY_SUCCESS);
    }
    @Bean
    public Binding bindPoint() {
        return BindingBuilder.bind(pointQueue()).to(payExchange()).with(MqConstants.RK_PAY_SUCCESS);
    }
    @Bean
    public Binding bindNotify() {
        return BindingBuilder.bind(notifyQueue()).to(payExchange()).with(MqConstants.RK_PAY_SUCCESS);
    }

    /* ---- 死信队列本身 ---- */
    @Bean public Queue ticketDlq() { return QueueBuilder.durable(MqConstants.Q_TICKET_CREATE_DLQ).build(); }
    @Bean public Queue pointDlq() { return QueueBuilder.durable(MqConstants.Q_POINT_ADD_DLQ).build(); }
    @Bean public Queue notifyDlq() { return QueueBuilder.durable(MqConstants.Q_NOTIFY_SEND_DLQ).build(); }

    @Bean public Binding bindTicketDlq() {
        return BindingBuilder.bind(ticketDlq()).to(dlxExchange()).with(MqConstants.Q_TICKET_CREATE_DLQ);
    }
    @Bean public Binding bindPointDlq() {
        return BindingBuilder.bind(pointDlq()).to(dlxExchange()).with(MqConstants.Q_POINT_ADD_DLQ);
    }
    @Bean public Binding bindNotifyDlq() {
        return BindingBuilder.bind(notifyDlq()).to(dlxExchange()).with(MqConstants.Q_NOTIFY_SEND_DLQ);
    }

    /* ---- 第6卷：秒杀下单（削峰填谷） ---- */
    @Bean
    public DirectExchange seckillExchange() {
        return ExchangeBuilder.directExchange(MqConstants.SECKILL_EXCHANGE).durable(true).build();
    }

    /**
     * 秒杀下单队列：消费端手动 ACK，业务失败已在消费者内补偿（回补库存/删标记/写FAIL）后仍 ack，
     * 语义是「不让一条注定失败的消息无限重投」，故不再挂死信交换机。
     */
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(MqConstants.Q_SECKILL_ORDER).build();
    }

    @Bean
    public Binding bindSeckillOrder() {
        return BindingBuilder.bind(seckillOrderQueue()).to(seckillExchange()).with(MqConstants.RK_SECKILL_ORDER);
    }
}
