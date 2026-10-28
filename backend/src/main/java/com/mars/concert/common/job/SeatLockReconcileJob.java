package com.mars.concert.common.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.order.entity.Orders;
import com.mars.concert.order.enums.OrderStatus;
import com.mars.concert.order.mapper.OrderMapper;
import com.mars.concert.show.inventory.entity.EventSeat;
import com.mars.concert.show.inventory.enums.SeatStatus;
import com.mars.concert.show.inventory.mapper.EventSeatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 锁死座位补偿任务（对账）：Redis 锁 TTL 到了但 DB 仍记 LOCKED、或 Redis 与 DB 不一致时，
 * 以订单账本为准对齐两侧状态，保证 Redis 丢数据后可从 DB 重建。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatLockReconcileJob {

    private final EventSeatMapper eventSeatMapper;
    private final OrderMapper orderMapper;
    private final StringRedisTemplate redis;

    @Scheduled(fixedDelay = 120_000)
    public void reconcileLocks() {
        // 查 DB 中 lock_expire 已过但 status=LOCKED 的座位
        List<EventSeat> stuck = eventSeatMapper.selectList(Wrappers.<EventSeat>lambdaQuery()
                .eq(EventSeat::getStatus, SeatStatus.LOCKED.name())
                .lt(EventSeat::getLockExpire, LocalDateTime.now())
                .last("LIMIT 200"));
        for (EventSeat s : stuck) {
            try {
                reconcileOne(s);
            } catch (Exception e) {
                log.error("座位对账失败 eventSeatId={}", s.getId(), e);
            }
        }
    }

    private void reconcileOne(EventSeat s) {
        String owner = s.getLockOwner();
        String orderNo = owner != null && owner.contains(":") ? owner.substring(owner.indexOf(':') + 1) : null;
        Orders order = orderNo == null ? null : orderMapper.selectOne(
                Wrappers.<Orders>lambdaQuery().eq(Orders::getOrderNo, orderNo));

        String hashKey = String.format(RedisKeys.EVENT_SEAT_HASH, s.getEventId());
        String lockKey = String.format(RedisKeys.SEAT_LOCK, s.getEventId(), s.getSeatId());
        String stockKey = String.format(RedisKeys.EVENT_TIER_STOCK, s.getEventId(), s.getTierId());

        if (order != null && (OrderStatus.PAID.name().equals(order.getStatus())
                || OrderStatus.TICKETED.name().equals(order.getStatus())
                || OrderStatus.FINISHED.name().equals(order.getStatus()))) {
            // 订单已支付 → 以订单为准置 SOLD
            markStatus(s, SeatStatus.SOLD);
            redis.opsForHash().put(hashKey, String.valueOf(s.getSeatId()), SeatStatus.SOLD.code());
            redis.delete(lockKey);
            log.info("对账：座位 {} 订单已支付，置 SOLD", s.getSeatId());
        } else if (order == null || OrderStatus.CANCELLED.name().equals(order.getStatus())) {
            // 订单不存在/已取消 → 两侧置 FREE 并回补余票
            markStatus(s, SeatStatus.FREE);
            redis.opsForHash().put(hashKey, String.valueOf(s.getSeatId()), SeatStatus.FREE.code());
            redis.delete(lockKey);
            redis.opsForValue().increment(stockKey);
            log.info("对账：座位 {} 订单已关闭，置 FREE", s.getSeatId());
        }
        // 订单仍 WAIT_PAY → 由延迟队列/兜底关单处理，这里不动
    }

    private void markStatus(EventSeat s, SeatStatus to) {
        eventSeatMapper.update(null, Wrappers.<EventSeat>lambdaUpdate()
                .eq(EventSeat::getId, s.getId())
                .set(EventSeat::getStatus, to.name())
                .set(EventSeat::getLockOwner, null)
                .set(EventSeat::getLockExpire, null));
    }
}
