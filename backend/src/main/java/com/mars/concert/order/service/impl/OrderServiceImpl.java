package com.mars.concert.order.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mars.concert.common.enums.CommonErrorCode;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.common.result.PageResult;
import com.mars.concert.common.utils.OrderNoGenerator;
import com.mars.concert.common.utils.RedisService;
import com.mars.concert.order.entity.OrderItem;
import com.mars.concert.order.entity.OrderLog;
import com.mars.concert.order.entity.Orders;
import com.mars.concert.order.enums.OrderErrorCode;
import com.mars.concert.order.enums.OrderEvent;
import com.mars.concert.order.enums.OrderStatus;
import com.mars.concert.order.mapper.OrderItemMapper;
import com.mars.concert.order.mapper.OrderLogMapper;
import com.mars.concert.order.mapper.OrderMapper;
import com.mars.concert.order.model.dto.CreateOrderDTO;
import com.mars.concert.order.model.dto.OrderItemDTO;
import com.mars.concert.order.model.dto.PricePreviewDTO;
import com.mars.concert.order.model.vo.OrderExportRow;
import com.mars.concert.order.model.vo.OrderItemVO;
import com.mars.concert.order.model.vo.OrderVO;
import com.mars.concert.order.service.*;
import com.mars.concert.order.statemachine.OrderStateMachine;
import com.mars.concert.promotion.entity.CouponTemplate;
import com.mars.concert.promotion.entity.UserCoupon;
import com.mars.concert.promotion.service.CouponService;
import com.mars.concert.show.entity.Event;
import com.mars.concert.show.entity.EventTier;
import com.mars.concert.show.entity.TicketTier;
import com.mars.concert.show.enums.EventStatus;
import com.mars.concert.show.inventory.entity.EventSeat;
import com.mars.concert.show.inventory.service.SeatInventoryService;
import com.mars.concert.show.mapper.EventMapper;
import com.mars.concert.show.mapper.EventTierMapper;
import com.mars.concert.show.mapper.TicketTierMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Orders> implements OrderService {

    private final EventMapper eventMapper;
    private final EventTierMapper eventTierMapper;
    private final TicketTierMapper ticketTierMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderLogMapper orderLogMapper;
    private final CartService cartService;
    private final PriceEngine priceEngine;
    private final RedisService redis;
    private final SeatInventoryService seatInventoryService;
    private final OrderDelayService orderDelayService;
    private final CouponService couponService;

    private static final long ORDER_TTL_MINUTES = 15;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createOrder(Long userId, CreateOrderDTO dto) {
        // ① 幂等：Redis SETNX 锁 + DB 唯一索引双保险
        if (dto.getIdempotentKey() == null || dto.getIdempotentKey().isBlank()) {
            throw new BusinessException(OrderErrorCode.REPEAT_SUBMIT);
        }
        String idemRedisKey = "mars:order:idem:" + userId + ":" + dto.getIdempotentKey();
        Boolean first = redis.setIfAbsent(idemRedisKey, "1", ORDER_TTL_MINUTES * 60);
        if (Boolean.FALSE.equals(first)) {
            Orders exist = baseMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                    .eq(Orders::getUserId, userId).eq(Orders::getIdempotentKey, dto.getIdempotentKey()));
            if (exist != null) return exist.getOrderNo();
            throw new BusinessException(OrderErrorCode.REPEAT_SUBMIT);
        }

        // ② 校验场次可售
        Event event = eventMapper.selectById(dto.getEventId());
        if (event == null) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        LocalDateTime now = LocalDateTime.now();
        boolean saleable = (event.getStatus() == EventStatus.PRESALE.getCode()
                || event.getStatus() == EventStatus.ONSALE.getCode())
                && (event.getSaleStart() == null || !now.isBefore(event.getSaleStart()))
                && (event.getSaleEnd() == null || !now.isAfter(event.getSaleEnd()));
        if (!saleable) throw new BusinessException(OrderErrorCode.EVENT_NOT_SALE);

        // 预生成订单号：选座下单时锁 owner 用它关联订单（手册 §9）
        String orderNo = OrderNoGenerator.next();
        boolean seatOrder = dto.getSeatIds() != null && !dto.getSeatIds().isEmpty();

        // ③ 合并同票档数量 + 限购校验；选座下单先 Lua 原子锁座（Redis 余票-1），再由座位推导明细
        Map<Long, Integer> qtyMap = new LinkedHashMap<>();
        List<EventSeat> seatRows = List.of();
        if (seatOrder) {
            try {
                seatInventoryService.lockSeats(userId, dto.getEventId(), dto.getSeatIds(), orderNo);
                seatRows = seatInventoryService.listEventSeats(dto.getEventId(), dto.getSeatIds());
                if (seatRows.size() != dto.getSeatIds().size()) {
                    throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(), "存在无效座位");
                }
                for (EventSeat es : seatRows) {
                    qtyMap.merge(es.getTierId(), 1, Integer::sum);
                }
            } catch (RuntimeException e) {
                // 锁座后任何失败都要释放预占，避免锁死座位
                seatInventoryService.releaseByOrderNo(orderNo);
                throw e;
            }
        } else {
            if (dto.getItems() == null || dto.getItems().isEmpty()) {
                throw new BusinessException(OrderErrorCode.CART_EMPTY);
            }
            for (OrderItemDTO it : dto.getItems()) {
                qtyMap.merge(it.getTierId(), it.getQuantity(), Integer::sum);
            }
        }
        int totalQty = qtyMap.values().stream().mapToInt(Integer::intValue).sum();
        if (totalQty > event.getLimitPerOrder()) {
            if (seatOrder) seatInventoryService.releaseByOrderNo(orderNo);
            throw new BusinessException(OrderErrorCode.OVER_LIMIT);
        }

        try {
            return doCreateOrder(userId, dto, event, orderNo, seatOrder, seatRows, qtyMap, now);
        } catch (RuntimeException e) {
            if (seatOrder) seatInventoryService.releaseByOrderNo(orderNo);
            throw e;
        }
    }

    private String doCreateOrder(Long userId, CreateOrderDTO dto, Event event, String orderNo,
                                 boolean seatOrder, List<EventSeat> seatRows,
                                 Map<Long, Integer> qtyMap, LocalDateTime now) {

        // ④ 查票档真实价格与库存
        List<PriceLine> lines = new ArrayList<>();
        Map<Long, EventTier> etMap = new HashMap<>();
        for (Map.Entry<Long, Integer> e : qtyMap.entrySet()) {
            EventTier et = eventTierMapper.selectOne(Wrappers.<EventTier>lambdaQuery()
                    .eq(EventTier::getEventId, dto.getEventId())
                    .eq(EventTier::getTierId, e.getKey())
                    .eq(EventTier::getStatus, 1));
            if (et == null) throw new BusinessException(OrderErrorCode.TIER_NOT_ON_SALE);
            etMap.put(e.getKey(), et);
            lines.add(new PriceLine(e.getKey(), et.getPrice(), e.getValue()));
        }

        // ⑤ 服务端算价（第6卷：带券。券的归属/状态/有效期先校验，范围/门槛由价格引擎判定）
        UserCoupon uc = null;
        CouponTemplate tpl = null;
        if (dto.getUserCouponId() != null) {
            uc = couponService.checkUsable(userId, dto.getUserCouponId());
            tpl = couponService.getTemplate(uc.getTemplateId());
        }
        OrderPrice price = priceEngine.calculate(lines, dto.getEventId(), uc, tpl);

        // ⑥ 扣库存：站票走 DB 条件更新防超卖；坐票锁座时已在 Redis 原子扣减（手册 §9），支付成功才落 DB sold
        if (!seatOrder) {
            for (Map.Entry<Long, Integer> e : qtyMap.entrySet()) {
                EventTier et = etMap.get(e.getKey());
                int rows = eventTierMapper.deductStock(et.getId(), e.getValue());
                if (rows == 0) {
                    throw new BusinessException(OrderErrorCode.STOCK_NOT_ENOUGH);
                }
            }
        }

        // ⑥.5 核销券：条件更新防一单多用/并发同用；affected=0 抛异常回滚整单（库存随事务回补）
        if (uc != null) {
            couponService.markUsed(uc.getId(), userId, orderNo);
        }

        // ⑦ 写订单主表
        Orders order = new Orders();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setEventId(dto.getEventId());
        order.setTotalAmount(price.getTotalAmount());
        order.setServiceAmount(price.getServiceAmount());
        order.setDiscountAmount(price.getDiscountAmount());
        order.setPayAmount(price.getPayAmount());
        order.setStatus(OrderStatus.WAIT_PAY.name());
        order.setIdempotentKey(dto.getIdempotentKey());
        order.setRemark(dto.getRemark());
        order.setExpireTime(now.plusMinutes(ORDER_TTL_MINUTES));
        baseMapper.insert(order);

        // ⑧ 写明细：选座订单一座一条（写 seat_id）；站票按票档数量
        if (seatOrder) {
            for (EventSeat es : seatRows) {
                EventTier et = etMap.get(es.getTierId());
                OrderItem oi = new OrderItem();
                oi.setOrderId(order.getId());
                oi.setEventId(dto.getEventId());
                oi.setTierId(es.getTierId());
                oi.setSeatId(es.getSeatId());
                oi.setUnitPrice(et.getPrice());
                oi.setQuantity(1);
                oi.setSubTotal(et.getPrice());
                orderItemMapper.insert(oi);
            }
        } else {
            for (OrderItemDTO it : dto.getItems()) {
                EventTier et = etMap.get(it.getTierId());
                OrderItem oi = new OrderItem();
                oi.setOrderId(order.getId());
                oi.setEventId(dto.getEventId());
                oi.setTierId(it.getTierId());
                oi.setViewerId(it.getViewerId());
                oi.setUnitPrice(et.getPrice());
                oi.setQuantity(it.getQuantity());
                oi.setSubTotal(et.getPrice().multiply(java.math.BigDecimal.valueOf(it.getQuantity())));
                orderItemMapper.insert(oi);
            }
        }

        // ⑨ 写状态日志（CREATE 是创建动作，event 字段写 USER_CANCEL 不太合适，手册示例也是如此）
        saveLog(order.getId(), null, OrderStatus.WAIT_PAY, OrderEvent.USER_CANCEL, "CREATE", userId.toString());

        // ⑩ 清空购物车
        cartService.clear(userId);

        // ⑪ 事务提交后投递延迟关单消息（第4卷 §10：Redisson 延迟队列 + 定时任务兜底双保险）
        String no = order.getOrderNo();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orderDelayService.sendCloseCheck(no, ORDER_TTL_MINUTES * 60);
            }
        });

        return order.getOrderNo();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, String orderNo) {
        Orders order = getOwnedOrder(userId, orderNo);
        OrderStatus from = OrderStatus.valueOf(order.getStatus());
        OrderStatus to = OrderStateMachine.next(from, OrderEvent.USER_CANCEL);

        // 释放库存：站票回补 DB；坐票由 releaseByOrderNo 释放 Redis 锁并对齐 DB（锁座时未扣 DB sold）
        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, order.getId()));
        for (OrderItem it : items) {
            if (it.getSeatId() == null) {
                eventTierMapper.addStock(it.getEventId(), it.getTierId(), it.getQuantity());
            }
        }
        order.setStatus(to.name());
        baseMapper.updateById(order);
        saveLog(order.getId(), from, to, OrderEvent.USER_CANCEL, "USER_CANCEL", userId.toString());
        seatInventoryService.releaseByOrderNo(orderNo);
        // 第6卷：取消订单回退券（幂等）
        couponService.refundByOrderNo(orderNo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transit(Long orderId, OrderEvent event, String operator) {
        Orders order = baseMapper.selectById(orderId);
        if (order == null) throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
        OrderStatus from = OrderStatus.valueOf(order.getStatus());
        OrderStatus to = OrderStateMachine.next(from, event);
        order.setStatus(to.name());
        baseMapper.updateById(order);
        saveLog(order.getId(), from, to, event, event.name(), operator);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeIfTimeout(String orderNo) {
        Orders o = baseMapper.selectOne(Wrappers.<Orders>lambdaQuery().eq(Orders::getOrderNo, orderNo));
        // 幂等：已支付/已关跳过
        if (o == null || !OrderStatus.WAIT_PAY.name().equals(o.getStatus())) return;
        OrderStatus from = OrderStatus.WAIT_PAY;
        OrderStatus to = OrderStateMachine.next(from, OrderEvent.TIMEOUT_CANCEL);

        // 站票回补 EventTier 库存；坐票由调用方 releaseByOrderNo 释放 Redis 并对齐 DB
        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, o.getId()));
        for (OrderItem it : items) {
            if (it.getSeatId() == null) {
                eventTierMapper.addStock(it.getEventId(), it.getTierId(), it.getQuantity());
            }
        }
        o.setStatus(to.name());
        baseMapper.updateById(o);
        saveLog(o.getId(), from, to, OrderEvent.TIMEOUT_CANCEL, "TIMEOUT", "SYSTEM");
        // 第6卷：超时关单回退券（幂等）
        couponService.refundByOrderNo(orderNo);
        log.info("订单 {} 超时自动关闭", orderNo);
    }

    @Override
    public List<Orders> listExpiredWaitPay(int limit) {
        return baseMapper.selectList(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getStatus, OrderStatus.WAIT_PAY.name())
                .lt(Orders::getExpireTime, LocalDateTime.now())
                .orderByAsc(Orders::getExpireTime)
                .last("LIMIT " + limit));
    }

    @Override
    public PageResult<OrderVO> pageMine(Long userId, String status, long pageNum, long pageSize) {
        Page<Orders> page = new Page<>(pageNum, pageSize);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Orders> q =
                Wrappers.<Orders>lambdaQuery().eq(Orders::getUserId, userId);
        if (status != null && !status.isBlank()) q.eq(Orders::getStatus, status);
        q.orderByDesc(Orders::getCreateTime);
        Page<Orders> p = this.page(page, q);
        List<OrderVO> list = p.getRecords().stream().map(o -> assembleOrder(o)).toList();
        return PageResult.of(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    @Override
    public OrderVO detailMine(Long userId, String orderNo) {
        Orders order = getOwnedOrder(userId, orderNo);
        return assembleOrder(order);
    }

    @Override
    public OrderPrice pricePreview(Long userId, PricePreviewDTO dto) {
        // 合并同票档数量，查 event_tier 真实价（与下单同口径，防止前端改价）
        Map<Long, Integer> qtyMap = new LinkedHashMap<>();
        for (OrderItemDTO it : dto.getItems()) {
            qtyMap.merge(it.getTierId(), it.getQuantity(), Integer::sum);
        }
        List<PriceLine> lines = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : qtyMap.entrySet()) {
            EventTier et = eventTierMapper.selectOne(Wrappers.<EventTier>lambdaQuery()
                    .eq(EventTier::getEventId, dto.getEventId())
                    .eq(EventTier::getTierId, e.getKey())
                    .eq(EventTier::getStatus, 1));
            if (et == null) throw new BusinessException(OrderErrorCode.TIER_NOT_ON_SALE);
            lines.add(new PriceLine(e.getKey(), et.getPrice(), e.getValue()));
        }
        UserCoupon uc = null;
        CouponTemplate tpl = null;
        if (dto.getUserCouponId() != null) {
            uc = couponService.checkUsable(userId, dto.getUserCouponId());
            tpl = couponService.getTemplate(uc.getTemplateId());
        }
        return priceEngine.calculate(lines, dto.getEventId(), uc, tpl);
    }

    @Override
    public List<OrderExportRow> listForExport(String status) {
        var q = Wrappers.<Orders>lambdaQuery();
        if (status != null && !status.isBlank()) q.eq(Orders::getStatus, status);
        q.orderByDesc(Orders::getCreateTime).last("LIMIT 5000");   // 学习期限量防 OOM
        List<Orders> orders = baseMapper.selectList(q);

        List<OrderExportRow> rows = new ArrayList<>();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (Orders o : orders) {
            OrderExportRow row = new OrderExportRow();
            row.setOrderNo(o.getOrderNo());
            row.setUserId(o.getUserId());
            Event e = eventMapper.selectById(o.getEventId());
            row.setEventTitle(e == null ? "" : e.getTitle());
            row.setPayAmount(o.getPayAmount());
            row.setStatus(o.getStatus());
            row.setCreateTime(o.getCreateTime() == null ? "" : o.getCreateTime().format(fmt));
            rows.add(row);
        }
        return rows;
    }

    private Orders getOwnedOrder(Long userId, String orderNo) {
        Orders order = baseMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getOrderNo, orderNo).eq(Orders::getUserId, userId));
        if (order == null) throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
        return order;
    }

    private OrderVO assembleOrder(Orders o) {
        OrderVO vo = new OrderVO();
        vo.setOrderNo(o.getOrderNo());
        vo.setEventId(o.getEventId());
        Event e = eventMapper.selectById(o.getEventId());
        if (e != null) vo.setEventTitle(e.getTitle());
        vo.setStatus(o.getStatus());
        vo.setTotalAmount(o.getTotalAmount());
        vo.setServiceAmount(o.getServiceAmount());
        vo.setDiscountAmount(o.getDiscountAmount());
        vo.setPayAmount(o.getPayAmount());
        vo.setExpireTime(o.getExpireTime());
        vo.setPayTime(o.getPayTime());
        vo.setCreateTime(o.getCreateTime());

        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, o.getId()));
        List<OrderItemVO> itemVOs = new ArrayList<>();
        for (OrderItem it : items) {
            TicketTier t = ticketTierMapper.selectById(it.getTierId());
            OrderItemVO iv = new OrderItemVO();
            iv.setTierId(it.getTierId());
            iv.setTierName(t == null ? "票档" : t.getName());
            iv.setUnitPrice(it.getUnitPrice());
            iv.setQuantity(it.getQuantity());
            iv.setSubTotal(it.getSubTotal());
            itemVOs.add(iv);
        }
        vo.setItems(itemVOs);
        return vo;
    }

    private void saveLog(Long orderId, OrderStatus from, OrderStatus to, OrderEvent event, String remark, String operator) {
        OrderLog log = new OrderLog();
        log.setOrderId(orderId);
        log.setFromStatus(from == null ? null : from.name());
        log.setToStatus(to.name());
        log.setEvent(event.name());
        log.setRemark(remark);
        log.setOperator(operator);
        orderLogMapper.insert(log);
    }
}
