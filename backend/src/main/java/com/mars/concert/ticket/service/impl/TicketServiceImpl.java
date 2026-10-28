package com.mars.concert.ticket.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.common.utils.OrderNoGenerator;
import com.mars.concert.common.utils.RedisService;
import com.mars.concert.order.entity.OrderItem;
import com.mars.concert.order.entity.Orders;
import com.mars.concert.order.enums.OrderErrorCode;
import com.mars.concert.order.enums.OrderEvent;
import com.mars.concert.order.enums.OrderStatus;
import com.mars.concert.order.mapper.OrderItemMapper;
import com.mars.concert.order.mapper.OrderMapper;
import com.mars.concert.order.service.OrderService;
import com.mars.concert.show.entity.Event;
import com.mars.concert.show.inventory.entity.EventSeat;
import com.mars.concert.show.inventory.enums.SeatStatus;
import com.mars.concert.show.inventory.mapper.EventSeatMapper;
import com.mars.concert.show.mapper.EventMapper;
import com.mars.concert.ticket.entity.Ticket;
import com.mars.concert.ticket.enums.TicketErrorCode;
import com.mars.concert.ticket.mapper.TicketMapper;
import com.mars.concert.ticket.model.vo.TicketVO;
import com.mars.concert.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TicketMapper ticketMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderService orderService;
    private final EventSeatMapper eventSeatMapper;
    private final EventMapper eventMapper;
    private final StringRedisTemplate redis;
    private final RedisService redisService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void issueTickets(String orderNo) {
        Orders order = orderMapper.selectOne(Wrappers.<Orders>lambdaQuery().eq(Orders::getOrderNo, orderNo));
        if (order == null) throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);

        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, order.getId()));

        boolean anyNew = false;
        for (OrderItem it : items) {
            for (int i = 0; i < it.getQuantity(); i++) {
                // 幂等：同一 orderItem 已有票则跳过（消息重复投递安全）
                Long exist = ticketMapper.countByOrderItem(it.getId());
                if (exist != null && exist > i) continue;

                Ticket t = new Ticket();
                t.setTicketNo(OrderNoGenerator.ticketNo());
                t.setOrderId(order.getId());
                t.setOrderItemId(it.getId());
                t.setEventId(it.getEventId());
                t.setSeatId(it.getSeatId());
                t.setViewerId(it.getViewerId());
                // 64位随机(两段UUID去横线)，不可枚举，不落订单号明文
                t.setQrToken(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""));
                t.setStatus("VALID");
                ticketMapper.insert(t);
                anyNew = true;
            }
        }

        // 坐票：支付成功才真正落 SOLD（站票下单时已扣 DB sold_stock，无需再动）
        confirmInventorySold(order, items);

        // 仅 PAID 才推进到 TICKETED；重复消息(已 TICKETED)不重复迁移，保证幂等
        if (anyNew && OrderStatus.PAID.name().equals(order.getStatus())) {
            orderService.transit(order.getId(), OrderEvent.TICKET_SUCCESS, "SYSTEM");
        }
    }

    private void confirmInventorySold(Orders order, List<OrderItem> items) {
        for (OrderItem it : items) {
            if (it.getSeatId() == null) continue;
            String hashKey = String.format(RedisKeys.EVENT_SEAT_HASH, order.getEventId());
            String lockKey = String.format(RedisKeys.SEAT_LOCK, order.getEventId(), it.getSeatId());
            redis.opsForHash().put(hashKey, String.valueOf(it.getSeatId()), SeatStatus.SOLD.code());
            redis.delete(lockKey);
            eventSeatMapper.update(null, Wrappers.<EventSeat>lambdaUpdate()
                    .eq(EventSeat::getEventId, order.getEventId())
                    .eq(EventSeat::getSeatId, it.getSeatId())
                    .set(EventSeat::getStatus, SeatStatus.SOLD.name())
                    .set(EventSeat::getLockOwner, null)
                    .set(EventSeat::getLockExpire, null));
        }
    }

    @Override
    public Ticket getOwnedTicket(Long userId, String ticketNo) {
        Ticket t = ticketMapper.selectOne(Wrappers.<Ticket>lambdaQuery().eq(Ticket::getTicketNo, ticketNo));
        if (t == null) throw new BusinessException(TicketErrorCode.TICKET_NOT_FOUND);
        Orders order = orderMapper.selectById(t.getOrderId());
        // 必须本人：不属于本人一律按「不存在」处理，避免泄露票是否存在
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(TicketErrorCode.TICKET_NOT_FOUND);
        }
        return t;
    }

    @Override
    public List<TicketVO> listMine(Long userId) {
        List<Orders> orders = orderMapper.selectList(Wrappers.<Orders>lambdaQuery().eq(Orders::getUserId, userId));
        if (orders.isEmpty()) return List.of();
        List<Long> orderIds = orders.stream().map(Orders::getId).toList();
        List<Ticket> tickets = ticketMapper.selectList(Wrappers.<Ticket>lambdaQuery()
                .in(Ticket::getOrderId, orderIds)
                .orderByDesc(Ticket::getCreateTime));
        return tickets.stream().map(t -> {
            TicketVO vo = new TicketVO();
            vo.setTicketNo(t.getTicketNo());
            vo.setEventId(t.getEventId());
            Event e = eventMapper.selectById(t.getEventId());
            if (e != null) vo.setEventTitle(e.getTitle());
            vo.setSeatId(t.getSeatId());
            vo.setQrToken(t.getQrToken());
            vo.setStatus(t.getStatus());
            vo.setCheckinTime(t.getCheckinTime());
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkin(String token, String device) {
        Ticket t = ticketMapper.selectOne(Wrappers.<Ticket>lambdaQuery().eq(Ticket::getQrToken, token));
        if (t == null) throw new BusinessException(TicketErrorCode.TICKET_NOT_FOUND);

        // Redis 原子防重（开场高峰集中入场，必须抗并发）
        Boolean first = redisService.setIfAbsent(RedisKeys.TICKET_USED + token, "1", 24 * 3600L);
        if (Boolean.FALSE.equals(first)) {
            throw new BusinessException(TicketErrorCode.TICKET_USED);
        }
        if (!"VALID".equals(t.getStatus())) {
            throw new BusinessException(TicketErrorCode.TICKET_NOT_VALID);
        }
        // DB 条件更新兜底：并发下只有一条 UPDATE ... WHERE status='VALID' 生效
        int rows = ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
                .eq(Ticket::getId, t.getId())
                .eq(Ticket::getStatus, "VALID")
                .set(Ticket::getStatus, "USED")
                .set(Ticket::getCheckinTime, LocalDateTime.now())
                .set(Ticket::getCheckinDevice, device));
        if (rows == 0) {
            throw new BusinessException(TicketErrorCode.TICKET_USED);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRefunded(Long orderId) {
        ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
                .eq(Ticket::getOrderId, orderId)
                .ne(Ticket::getStatus, "REFUNDED")
                .set(Ticket::getStatus, "REFUNDED"));
    }
}
