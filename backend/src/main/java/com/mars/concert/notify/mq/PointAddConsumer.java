package com.mars.concert.notify.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.concert.common.mq.MqConstants;
import com.mars.concert.common.mq.event.PaySuccessEvent;
import com.mars.concert.promotion.service.PointService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 积分消费者：支付成功返积分（第6卷 §6.4，1元=1分）。
 * pm_point_log uk_type_biz(ORDER_EARN, orderNo) 唯一索引保证重复投递只加一次；
 * 手动 ACK，业务异常进死信。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointAddConsumer {

    private final ObjectMapper objectMapper;
    private final PointService pointService;

    @RabbitListener(queues = MqConstants.Q_POINT_ADD)
    public void on(String body, Channel channel,
                   @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            PaySuccessEvent e = objectMapper.readValue(body, PaySuccessEvent.class);
            pointService.earn(e.getUserId(), new BigDecimal(e.getAmount()).intValue(),
                    "ORDER_EARN", e.getOrderNo());
            channel.basicAck(tag, false);
        } catch (DuplicateKeyException dup) {
            // 同订单已返过积分：幂等成功，直接 ACK
            log.info("积分已返过，幂等ACK body={}", body);
            channel.basicAck(tag, false);
        } catch (Exception ex) {
            log.error("加积分消费失败，转入死信 body={}", body, ex);
            channel.basicNack(tag, false, false);
        }
    }
}
