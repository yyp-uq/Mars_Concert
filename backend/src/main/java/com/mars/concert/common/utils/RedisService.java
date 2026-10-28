package com.mars.concert.common.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RedisService {
    private final RedisTemplate<String, Object> redisTemplate;

    public void set(String key, Object value, long ttlSeconds) {
        redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }
    public Object get(String key) { return redisTemplate.opsForValue().get(key); }
    public Boolean delete(String key) { return redisTemplate.delete(key); }
    public Boolean exists(String key) { return redisTemplate.hasKey(key); }
    public Long incr(String key) { return redisTemplate.opsForValue().increment(key); }
    public void expire(String key, long ttlSeconds) {
        redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
    }

    /** 幂等锁：SETNX + TTL，返回 true 表示抢锁成功 */
    public Boolean setIfAbsent(String key, Object value, long ttlSeconds) {
        return redisTemplate.opsForValue().setIfAbsent(key, value, ttlSeconds, TimeUnit.SECONDS);
    }
}
