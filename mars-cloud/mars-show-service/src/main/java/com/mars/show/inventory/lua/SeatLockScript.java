package com.mars.show.inventory.lua;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 锁座/释放 Lua 脚本（原子性核心）。
 * 「先 GET 判断 FREE，再 SET 成 LOCKED」是两步，并发下可能两个请求都锁成功（一座两锁）；
 * Lua 在 Redis 内单线程一次性执行「判断+修改」，天然原子。
 *
 * 按手册第5节建议：lockKey 不在脚本里用 string.match 拼，而是作为 KEYS[3] 显式传入，更稳妥。
 */
@Component
public class SeatLockScript {

    /**
     * KEYS[1]=座位Hash  KEYS[2]=票档余票string  KEYS[3]=座位锁key
     * ARGV[1]=seatId  ARGV[2]=owner(userId:orderNo)  ARGV[3]=ttl秒
     * 返回码：1成功 0座位非空闲(已售) -1已被他人锁定
     */
    private static final String LOCK_LUA = """
        local status = redis.call('HGET', KEYS[1], ARGV[1])
        if status == 'S' then return 0 end
        if status == 'L' then
          local owner = redis.call('GET', KEYS[3])
          if owner and owner ~= ARGV[2] then return -1 end
        end
        redis.call('HSET', KEYS[1], ARGV[1], 'L')
        redis.call('SET', KEYS[3], ARGV[2], 'EX', tonumber(ARGV[3]))
        redis.call('DECR', KEYS[2])
        return 1
        """;

    /**
     * 释放：KEYS[1]=Hash KEYS[2]=余票 KEYS[3]=锁key  ARGV[1]=seatId ARGV[2]=owner
     * 只有锁持有者能释放，返回1成功 0非本人锁
     */
    private static final String RELEASE_LUA = """
        local owner = redis.call('GET', KEYS[3])
        if not owner or owner ~= ARGV[2] then return 0 end
        redis.call('HSET', KEYS[1], ARGV[1], 'F')
        redis.call('DEL', KEYS[3])
        redis.call('INCR', KEYS[2])
        return 1
        """;

    private final DefaultRedisScript<Long> lockScript = new DefaultRedisScript<>(LOCK_LUA, Long.class);
    private final DefaultRedisScript<Long> releaseScript = new DefaultRedisScript<>(RELEASE_LUA, Long.class);

    public Long lock(StringRedisTemplate redis, List<String> keys, String seatId, String owner, int ttl) {
        return redis.execute(lockScript, keys, seatId, owner, String.valueOf(ttl));
    }

    public Long release(StringRedisTemplate redis, List<String> keys, String seatId, String owner) {
        return redis.execute(releaseScript, keys, seatId, owner);
    }
}
