package com.mars.concert.community.ws;

import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.community.model.message.DanmakuMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 弹幕发布者：把消息发到 Redis 频道 mars:channel:danmaku:{eventId}。
 * 所有实例都订阅该频道（见 DanmakuSubscriberConfig），
 * 这样不管用户连到哪个实例，弹幕都能全员收到（集群广播）。
 * 序列化走 RedisConfig 的 GenericJackson2JsonRedisSerializer（JSON 带类型信息）。
 */
@Component
@RequiredArgsConstructor
public class DanmakuRedisPublisher {

    private final RedisTemplate<String, Object> redisTemplate;

    public void publish(Long eventId, DanmakuMessage msg) {
        redisTemplate.convertAndSend(RedisKeys.DANMAKU_CHANNEL + eventId, msg);
    }
}
