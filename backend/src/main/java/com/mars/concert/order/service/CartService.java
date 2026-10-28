package com.mars.concert.order.service;

import com.mars.concert.common.enums.CommonErrorCode;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.order.model.vo.CartItemVO;
import com.mars.concert.order.model.vo.CartVO;
import com.mars.concert.show.entity.EventTier;
import com.mars.concert.show.entity.TicketTier;
import com.mars.concert.show.mapper.EventTierMapper;
import com.mars.concert.show.mapper.TicketTierMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

/**
 * 购物车：Redis Hash
 *   key   = mars:cart:{userId}
 *   field = {eventId}:{tierId}
 *   value = 数量
 * 本卷只暂存，不锁库存。提交订单时才扣。
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final EventTierMapper eventTierMapper;
    private final TicketTierMapper ticketTierMapper;

    private String key(Long uid) { return "mars:cart:" + uid; }
    private String field(Long eventId, Long tierId) { return eventId + ":" + tierId; }

    public void add(Long uid, Long eventId, Long tierId, int qty) {
        if (qty <= 0) throw new BusinessException(CommonErrorCode.PARAM_INVALID);
        String f = field(eventId, tierId);
        redisTemplate.opsForHash().increment(key(uid), f, qty);
        redisTemplate.expire(key(uid), Duration.ofDays(7));
    }

    public void updateQty(Long uid, Long eventId, Long tierId, int qty) {
        if (qty <= 0) { remove(uid, eventId, tierId); return; }
        redisTemplate.opsForHash().put(key(uid), field(eventId, tierId), qty);
    }

    public void remove(Long uid, Long eventId, Long tierId) {
        redisTemplate.opsForHash().delete(key(uid), field(eventId, tierId));
    }

    public void clear(Long uid) {
        redisTemplate.delete(key(uid));
    }

    /** 读取购物车并组装价格/票档名给前端 */
    public CartVO myCart(Long uid) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key(uid));
        java.util.List<CartItemVO> items = new ArrayList<>();
        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            String[] parts = e.getKey().toString().split(":");
            Long eventId = Long.valueOf(parts[0]);
            Long tierId = Long.valueOf(parts[1]);
            Integer qty = Integer.valueOf(e.getValue().toString());
            EventTier et = eventTierMapper.selectOne(Wrappers.<EventTier>lambdaQuery()
                    .eq(EventTier::getEventId, eventId).eq(EventTier::getTierId, tierId));
            if (et == null) continue;
            TicketTier tier = ticketTierMapper.selectById(tierId);
            CartItemVO vo = new CartItemVO();
            vo.setEventId(eventId);
            vo.setTierId(tierId);
            vo.setTierName(tier == null ? "票档" : tier.getName());
            vo.setPrice(et.getPrice());
            vo.setQuantity(qty);
            vo.setSubTotal(et.getPrice().multiply(BigDecimal.valueOf(qty)));
            items.add(vo);
        }
        CartVO vo = new CartVO();
        vo.setItems(items);
        vo.setTotal(items.stream().map(CartItemVO::getSubTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        return vo;
    }
}
