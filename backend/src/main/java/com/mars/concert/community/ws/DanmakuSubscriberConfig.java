package com.mars.concert.community.ws;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.community.model.message.DanmakuMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

/**
 * 弹幕订阅侧：每个实例都订阅所有场次频道（PatternTopic mars:channel:danmaku:*），
 * 收到后解析出 eventId 与消息体，推给本实例该场次的会话。
 *
 * 反序列化器必须与 RedisConfig 中 RedisTemplate 的 value 序列化保持一致
 * （带默认类型信息），否则取不回 DanmakuMessage 原类型。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DanmakuSubscriberConfig {

    private final DanmakuHandler danmakuHandler;

    /** Pub/Sub 监听容器（Spring Data Redis 提供，复用 Lettuce 连接工厂） */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory cf) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(cf);
        return container;
    }

    @Bean
    public Object danmakuListener(RedisMessageListenerContainer container) {
        // 与 RedisConfig 同款 ObjectMapper：保留类型信息才能还原 DanmakuMessage
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(om);

        container.addMessageListener((message, pattern) -> {
            try {
                String channel = new String(message.getChannel());
                Long eventId = Long.valueOf(channel.substring(channel.lastIndexOf(':') + 1));
                DanmakuMessage msg = (DanmakuMessage) serializer.deserialize(message.getBody());
                if (msg != null) danmakuHandler.localBroadcast(eventId, msg);
            } catch (Exception e) {
                log.warn("弹幕订阅消息处理失败", e);
            }
        }, new PatternTopic(RedisKeys.DANMAKU_CHANNEL + "*"));
        // 注册即完成，返回标记对象仅为满足 @Bean 方法签名
        return new Object();
    }
}
