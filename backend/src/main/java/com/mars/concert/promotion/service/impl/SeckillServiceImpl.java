package com.mars.concert.promotion.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.common.mq.MqConstants;
import com.mars.concert.common.utils.OrderNoGenerator;
import com.mars.concert.order.entity.OrderItem;
import com.mars.concert.order.entity.Orders;
import com.mars.concert.order.enums.OrderStatus;
import com.mars.concert.order.mapper.OrderItemMapper;
import com.mars.concert.order.mapper.OrderMapper;
import com.mars.concert.order.service.OrderDelayService;
import com.mars.concert.promotion.entity.SeckillActivity;
import com.mars.concert.promotion.enums.PromotionErrorCode;
import com.mars.concert.promotion.mapper.SeckillActivityMapper;
import com.mars.concert.promotion.model.message.SeckillMessage;
import com.mars.concert.promotion.seckill.SeckillScript;
import com.mars.concert.promotion.service.SeckillService;
import com.mars.concert.show.entity.EventTier;
import com.mars.concert.show.mapper.EventTierMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private static final long RESULT_TTL_SECONDS = 300;
    private static final long ORDER_TTL_MINUTES = 15;

    private final SeckillActivityMapper activityMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final EventTierMapper eventTierMapper;
    private final OrderDelayService orderDelayService;
    private final SeckillScript seckillScript;
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String execute(Long userId, Long actId) {
        SeckillActivity a = activityMapper.selectById(actId);
        if (a == null || a.getStatus() == null || a.getStatus() != 1) {
            throw new BusinessException(PromotionErrorCode.SECKILL_NOT_FOUND);
        }

        // ① Lua 原子：判时间窗 + 判重 + 扣 Redis 余量（高并发全挡在 Redis，不打 DB）
        String stockKey = String.format(RedisKeys.SECKILL_STOCK, actId);
        String userKey = String.format(RedisKeys.SECKILL_USER, actId, userId);
        // 学习期无预热任务：首次抢购用活动总库存兜底初始化
        if (Boolean.FALSE.equals(redis.hasKey(stockKey))) {
            redis.opsForValue().setIfAbsent(stockKey, String.valueOf(a.getTotalStock()));
        }
        long now = Instant.now().getEpochSecond();
        Long r = redis.execute(seckillScript.get(),
                List.of(stockKey, userKey),
                String.valueOf(userId), "1",
                String.valueOf(a.getStartTime().atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()),
                String.valueOf(a.getEndTime().atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()),
                String.valueOf(now));
        switch (r == null ? -99 : r.intValue()) {
            case -1 -> throw new BusinessException(PromotionErrorCode.SECKILL_SOLD_OUT);
            case -2 -> throw new BusinessException(PromotionErrorCode.SECKILL_BOUGHT);
            case -3 -> throw new BusinessException(PromotionErrorCode.SECKILL_NOT_START);
            case -4 -> throw new BusinessException(PromotionErrorCode.SECKILL_ENDED);
            case -99 -> throw new BusinessException(PromotionErrorCode.SECKILL_NOT_FOUND);
            default -> { /* 抢到，继续 */ }
        }

        // ② 投递 MQ 异步建单（削峰：瞬时写削成平稳写），立即返回排队凭证
        String ticket = UUID.randomUUID().toString();
        try {
            rabbitTemplate.convertAndSend(MqConstants.SECKILL_EXCHANGE, MqConstants.RK_SECKILL_ORDER,
                    objectMapper.writeValueAsString(new SeckillMessage(actId, userId, ticket)));
        } catch (Exception e) {
            // 发消息失败：回补 Redis 库存与用户标记，保证不超卖也不误占
            redis.opsForValue().increment(stockKey);
            redis.delete(userKey);
            throw new BusinessException(PromotionErrorCode.SECKILL_SOLD_OUT.getCode(), "排队失败，请重试");
        }
        redis.opsForValue().set(String.format(RedisKeys.SECKILL_RESULT, ticket),
                "QUEUED", RESULT_TTL_SECONDS, TimeUnit.SECONDS);
        return ticket;
    }

    @Override
    public String result(String ticket) {
        Object v = redis.opsForValue().get(String.format(RedisKeys.SECKILL_RESULT, ticket));
        return v == null ? "EXPIRED" : v.toString();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createSeckillOrder(SeckillMessage msg) {
        // ① 幂等：ticket 即幂等键，重复消费直接返回已建订单号
        String idemKey = "SECKILL-" + msg.getTicket();
        Orders exist = orderMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getIdempotentKey, idemKey));
        if (exist != null) return exist.getOrderNo();

        SeckillActivity a = activityMapper.selectById(msg.getActId());
        if (a == null) throw new BusinessException(PromotionErrorCode.SECKILL_NOT_FOUND);

        // ② DB 条件更新扣库存（Redis 已预减，这里按消费能力平稳落库，双保险防超卖）
        EventTier et = eventTierMapper.selectOne(Wrappers.<EventTier>lambdaQuery()
                .eq(EventTier::getEventId, a.getEventId())
                .eq(EventTier::getTierId, a.getTierId()));
        if (et == null || eventTierMapper.deductStock(et.getId(), 1) == 0) {
            throw new BusinessException(PromotionErrorCode.SECKILL_SOLD_OUT);
        }

        // ③ 建单：秒杀价直接成交，无服务费/优惠
        LocalDateTime now = LocalDateTime.now();
        Orders order = new Orders();
        order.setOrderNo(OrderNoGenerator.next());
        order.setUserId(msg.getUserId());
        order.setEventId(a.getEventId());
        order.setTotalAmount(a.getSeckillPrice());
        order.setServiceAmount(java.math.BigDecimal.ZERO);
        order.setDiscountAmount(java.math.BigDecimal.ZERO);
        order.setPayAmount(a.getSeckillPrice());
        order.setStatus(OrderStatus.WAIT_PAY.name());
        order.setIdempotentKey(idemKey);
        order.setRemark("秒杀活动#" + a.getId());
        order.setExpireTime(now.plusMinutes(ORDER_TTL_MINUTES));
        orderMapper.insert(order);

        OrderItem oi = new OrderItem();
        oi.setOrderId(order.getId());
        oi.setEventId(a.getEventId());
        oi.setTierId(a.getTierId());
        oi.setUnitPrice(a.getSeckillPrice());
        oi.setQuantity(1);
        oi.setSubTotal(a.getSeckillPrice());
        orderItemMapper.insert(oi);

        // ④ 事务提交后投递延迟关单（超时未支付自动关闭并回补库存，复用第4卷链路）
        String orderNo = order.getOrderNo();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orderDelayService.sendCloseCheck(orderNo, ORDER_TTL_MINUTES * 60);
            }
        });
        log.info("秒杀建单成功 actId={} userId={} orderNo={}", msg.getActId(), msg.getUserId(), orderNo);
        return orderNo;
    }
}
