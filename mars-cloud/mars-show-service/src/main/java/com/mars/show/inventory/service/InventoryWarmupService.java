package com.mars.show.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.common.constant.RedisKeys;
import com.mars.show.entity.EventTier;
import com.mars.show.inventory.entity.EventSeat;
import com.mars.show.inventory.enums.SeatStatus;
import com.mars.show.inventory.mapper.EventSeatMapper;
import com.mars.show.mapper.EventTierMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 库存预热：开售前把某场次座位状态与各票档余票从 DB 加载进 Redis。
 * 用 pipeline 批量写减少网络往返，上万座位几秒内完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryWarmupService {

    private final StringRedisTemplate stringRedis;
    private final EventSeatMapper eventSeatMapper;
    private final EventTierMapper eventTierMapper;

    /** 开售前调用（后台按钮 / 定时任务），把某场次座位与余票加载进 Redis */
    public void warmup(Long eventId) {
        // 1. 座位 Hash：批量 HSET（重新预热前清旧）
        String hashKey = String.format(RedisKeys.EVENT_SEAT_HASH, eventId);
        stringRedis.delete(hashKey);
        List<EventSeat> seats = eventSeatMapper.selectList(
                Wrappers.<EventSeat>lambdaQuery().eq(EventSeat::getEventId, eventId));

        List<EventSeat> batch = new ArrayList<>(500);
        for (EventSeat s : seats) {
            batch.add(s);
            if (batch.size() == 500) {
                flushSeat(hashKey, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) flushSeat(hashKey, batch);

        // 2. 各票档余票（站票也用这个）：remain = total - sold - locked，口径与扣减一致
        List<EventTier> tiers = eventTierMapper.selectList(
                Wrappers.<EventTier>lambdaQuery().eq(EventTier::getEventId, eventId));
        for (EventTier t : tiers) {
            int remain = t.getTotalStock() - t.getSoldStock() - t.getLockedStock();
            stringRedis.opsForValue().set(
                    String.format(RedisKeys.EVENT_TIER_STOCK, eventId, t.getTierId()),
                    String.valueOf(remain));
        }
        log.info("场次 {} 库存预热完成，座位 {} 个，票档 {} 个", eventId, seats.size(), tiers.size());
    }

    private void flushSeat(String hashKey, List<EventSeat> batch) {
        stringRedis.executePipelined((RedisCallback<Object>) conn -> {
            for (EventSeat s : batch) {
                // DB 存枚举 name（FREE/LOCKED/SOLD），Redis Hash 统一存 code（F/L/S），与 Lua 判断口径一致
                conn.hashCommands().hSet(
                        hashKey.getBytes(StandardCharsets.UTF_8),
                        String.valueOf(s.getSeatId()).getBytes(StandardCharsets.UTF_8),
                        SeatStatus.valueOf(s.getStatus()).code().getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
    }
}
