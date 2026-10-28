package com.mars.concert.notify.mq;

import com.mars.concert.common.mq.MqConstants;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 死信监控：三个 DLQ 各有只记录告警的监听器，便于发现失败消息并人工/补偿处理。
 * 消费后立即 ACK（避免死信消息长期 unacked）。
 */
@Slf4j
@Component
public class DeadLetterConsumer {

    @RabbitListener(queues = MqConstants.Q_TICKET_CREATE_DLQ)
    public void onTicketDlq(String body, Channel channel,
                            @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        log.error("【死信告警】出票失败消息: {}", body);
        channel.basicAck(tag, false);
    }

    @RabbitListener(queues = MqConstants.Q_POINT_ADD_DLQ)
    public void onPointDlq(String body, Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        log.error("【死信告警】加积分失败消息: {}", body);
        channel.basicAck(tag, false);
    }

    @RabbitListener(queues = MqConstants.Q_NOTIFY_SEND_DLQ)
    public void onNotifyDlq(String body, Channel channel,
                            @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        log.error("【死信告警】通知发送失败消息: {}", body);
        channel.basicAck(tag, false);
    }
}
