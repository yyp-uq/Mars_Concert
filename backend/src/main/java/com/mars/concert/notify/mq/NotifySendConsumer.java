package com.mars.concert.notify.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.concert.common.mq.MqConstants;
import com.mars.concert.common.mq.event.PaySuccessEvent;
import com.mars.concert.notify.service.NotifyService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 通知消费者：手动 ACK，异常进死信。通知异步做，绝不阻塞支付主事务。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifySendConsumer {

    private final NotifyService notifyService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = MqConstants.Q_NOTIFY_SEND)
    public void on(String body, Channel channel,
                   @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            PaySuccessEvent e = objectMapper.readValue(body, PaySuccessEvent.class);
            notifyService.sendOnPaySuccess(e.getUserId(), e.getOrderNo());
            channel.basicAck(tag, false);
        } catch (Exception ex) {
            log.error("通知发送失败，转入死信 body={}", body, ex);
            channel.basicNack(tag, false, false);
        }
    }
}
