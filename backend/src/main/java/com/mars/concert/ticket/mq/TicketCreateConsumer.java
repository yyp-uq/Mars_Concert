package com.mars.concert.ticket.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.concert.common.mq.MqConstants;
import com.mars.concert.common.mq.event.PaySuccessEvent;
import com.mars.concert.ticket.service.TicketService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 出票消费者：手动 ACK。成功即 ack；异常 nack(requeue=false) 进死信队列，由人工/补偿处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketCreateConsumer {

    private final TicketService ticketService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = MqConstants.Q_TICKET_CREATE)
    public void on(String body, Channel channel,
                   @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            PaySuccessEvent e = objectMapper.readValue(body, PaySuccessEvent.class);
            ticketService.issueTickets(e.getOrderNo());   // 内部幂等
            channel.basicAck(tag, false);
            log.info("出票成功 orderNo={}", e.getOrderNo());
        } catch (Exception ex) {
            log.error("出票消费失败，转入死信 body={}", body, ex);
            // nack 且不重回队列 → 进死信队列
            channel.basicNack(tag, false, false);
        }
    }
}
