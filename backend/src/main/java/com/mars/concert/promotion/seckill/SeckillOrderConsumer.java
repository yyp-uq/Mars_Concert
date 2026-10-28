package com.mars.concert.promotion.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.common.mq.MqConstants;
import com.mars.concert.promotion.model.message.SeckillMessage;
import com.mars.concert.promotion.service.SeckillService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀建单消费者：按自身能力速率消费，把 Redis 挡下的瞬时洪峰削成平稳 DB 写。
 * 建单失败已在 catch 里完成补偿（回补 Redis 库存 + 删用户标记 + 结果置 FAIL），
 * 业务失败直接 ACK，避免无限重投放大故障。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderConsumer {

    private static final long RESULT_TTL_SECONDS = 300;

    private final SeckillService seckillService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = MqConstants.Q_SECKILL_ORDER)
    public void on(String body, Channel channel,
                   @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        SeckillMessage msg;
        try {
            msg = objectMapper.readValue(body, SeckillMessage.class);
        } catch (Exception parseEx) {
            // 消息体损坏无法补偿，直接丢弃
            log.error("秒杀消息解析失败，丢弃 body={}", body, parseEx);
            channel.basicAck(tag, false);
            return;
        }
        String resultKey = String.format(RedisKeys.SECKILL_RESULT, msg.getTicket());
        try {
            String orderNo = seckillService.createSeckillOrder(msg);
            redis.opsForValue().set(resultKey, orderNo, RESULT_TTL_SECONDS, TimeUnit.SECONDS);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            // 建单失败补偿：回补 Redis 库存和用户标记，结果置 FAIL
            log.error("秒杀建单失败，执行补偿 msg={}", msg, e);
            redis.opsForValue().increment(String.format(RedisKeys.SECKILL_STOCK, msg.getActId()));
            redis.delete(String.format(RedisKeys.SECKILL_USER, msg.getActId(), msg.getUserId()));
            redis.opsForValue().set(resultKey, "FAIL", RESULT_TTL_SECONDS, TimeUnit.SECONDS);
            channel.basicAck(tag, false);
        }
    }
}
