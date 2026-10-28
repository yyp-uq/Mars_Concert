package com.mars.concert.promotion.seckill;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 秒杀 Lua：判重 + 扣余量一步原子完成（手册 §7.2）。
 * KEYS[1]=库存 KEYS[2]=用户标记
 * ARGV[1]=userId ARGV[2]=qty ARGV[3]=活动开始epoch ARGV[4]=结束epoch ARGV[5]=当前epoch
 * 返回：-1售罄 / -2已抢过 / -3未开始 / -4已结束 / 否则为剩余库存
 */
@Component
public class SeckillScript {

    private static final String LUA = """
        if tonumber(ARGV[5]) < tonumber(ARGV[3]) then return -3 end
        if tonumber(ARGV[5]) > tonumber(ARGV[4]) then return -4 end
        if redis.call('EXISTS', KEYS[2]) == 1 then return -2 end
        local left = tonumber(redis.call('GET', KEYS[1]) or '0')
        if left < tonumber(ARGV[2]) then return -1 end
        redis.call('DECRBY', KEYS[1], ARGV[2])
        redis.call('SET', KEYS[2], '1')
        return left - tonumber(ARGV[2])
        """;

    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA, Long.class);

    public DefaultRedisScript<Long> get() {
        return script;
    }
}
