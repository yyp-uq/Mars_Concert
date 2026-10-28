package com.mars.order.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mars.api.promotion.PromotionClient;
import com.mars.api.promotion.dto.CouponTemplateDTO;
import com.mars.api.promotion.dto.MarkUsedDTO;
import com.mars.api.promotion.dto.UserCouponDTO;
import com.mars.api.show.ShowClient;
import com.mars.api.show.dto.*;
import com.mars.api.show.enums.EventStatusEnum;
import com.mars.common.enums.CommonErrorCode;
import com.mars.common.exception.BusinessException;
import com.mars.common.result.PageResult;
import com.mars.common.result.Result;
import com.mars.common.utils.OrderNoGenerator;
import com.mars.common.utils.RedisService;
import com.mars.order.entity.OrderItem;
import com.mars.order.entity.OrderLog;
import com.mars.order.entity.Orders;
import com.mars.order.enums.OrderErrorCode;
import com.mars.order.enums.OrderEvent;
import com.mars.order.enums.OrderStatus;
import com.mars.order.mapper.OrderItemMapper;
import com.mars.order.mapper.OrderLogMapper;
import com.mars.order.mapper.OrderMapper;
import com.mars.order.model.dto.CreateOrderDTO;
import com.mars.order.model.dto.OrderItemDTO;
import com.mars.order.model.dto.PricePreviewDTO;
import com.mars.order.model.vo.AiOrderBriefVO;
import com.mars.order.model.vo.AiOrderDetailVO;
import com.mars.order.model.vo.AiOrderItemVO;
import com.mars.order.model.vo.AiRefundResultVO;
import com.mars.order.model.vo.OrderExportRow;
import com.mars.order.model.vo.OrderItemVO;
import com.mars.order.model.vo.OrderVO;
import com.mars.order.service.*;
import com.mars.order.statemachine.OrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 第8卷：订单域不再直查演出/营销库表，场次/票档/库存/券全部经 OpenFeign 远程调用。
 * 提供方业务异常以 Result(code!=0) 返回，unwrap 统一还原为 BusinessException 触发本地回滚；
 * 提供方宕机时走 mars-api 中的 fallbackFactory 友好降级。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Orders> implements OrderService {

    private final OrderItemMapper orderItemMapper;
    private final OrderLogMapper orderLogMapper;
    private final CartService cartService;
    private final PriceEngine priceEngine;
    private final RedisService redis;
    private final OrderDelayService orderDelayService;
    private final ShowClient showClient;
    private final PromotionClient promotionClient;

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

        // ② 远程查场次并校验可售
        EventInfoDTO event = unwrap(showClient.getEvent(dto.getEventId()));
        if (event == null) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        LocalDateTime now = LocalDateTime.now();
        boolean saleable = (event.getStatus() == EventStatusEnum.PRESALE.getCode()
                || event.getStatus() == EventStatusEnum.ONSALE.getCode())
                && (event.getSaleStart() == null || !now.isBefore(event.getSaleStart()))
                && (event.getSaleEnd() == null || !now.isAfter(event.getSaleEnd()));
        if (!saleable) throw new BusinessException(OrderErrorCode.EVENT_NOT_SALE);

        // 预生成订单号：选座下单时锁 owner 用它关联订单（手册 §9）
        String orderNo = OrderNoGenerator.next();
        boolean seatOrder = dto.getSeatIds() != null && !dto.getSeatIds().isEmpty();

        // ③ 合并同票档数量 + 限购校验；选座下单先远程 Lua 原子锁座（Redis 余票-1），再由座位推导明细
        Map<Long, Integer> qtyMap = new LinkedHashMap<>();
        List<SeatInfoDTO> seatRows = List.of();
        if (seatOrder) {
            try {
                unwrap(showClient.lockSeats(new LockSeatsDTO(userId, dto.getEventId(), dto.getSeatIds(), orderNo)));
                seatRows = unwrap(showClient.listEventSeats(new SeatListDTO(dto.getEventId(), dto.getSeatIds())));
                if (seatRows.size() != dto.getSeatIds().size()) {
                    throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(), "存在无效座位");
                }
                for (SeatInfoDTO es : seatRows) {
                    qtyMap.merge(es.getTierId(), 1, Integer::sum);
                }
            } catch (RuntimeException e) {
                // 锁座后任何失败都要释放预占，避免锁死座位
                showClient.releaseByOrderNo(orderNo);
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
            if (seatOrder) showClient.releaseByOrderNo(orderNo);
            throw new BusinessException(OrderErrorCode.OVER_LIMIT);
        }

        try {
            return doCreateOrder(userId, dto, event, orderNo, seatOrder, seatRows, qtyMap, now);
        } catch (RuntimeException e) {
            if (seatOrder) showClient.releaseByOrderNo(orderNo);
            throw e;
        }
    }

    private String doCreateOrder(Long userId, CreateOrderDTO dto, EventInfoDTO event, String orderNo,
                                 boolean seatOrder, List<SeatInfoDTO> seatRows,
                                 Map<Long, Integer> qtyMap, LocalDateTime now) {

        // ④ 远程查票档真实价格与库存（服务端算价，防止前端改价）
        List<PriceLine> lines = new ArrayList<>();
        Map<Long, TierInfoDTO> etMap = new HashMap<>();
        for (Map.Entry<Long, Integer> e : qtyMap.entrySet()) {
            TierInfoDTO et = unwrap(showClient.getTierInfo(dto.getEventId(), e.getKey()));
            if (et == null) throw new BusinessException(OrderErrorCode.TIER_NOT_ON_SALE);
            etMap.put(e.getKey(), et);
            lines.add(new PriceLine(e.getKey(), et.getPrice(), e.getValue()));
        }

        // ⑤ 服务端算价（带券。券的归属/状态/有效期由营销服务校验，范围/门槛由价格引擎判定）
        UserCouponDTO uc = null;
        CouponTemplateDTO tpl = null;
        if (dto.getUserCouponId() != null) {
            uc = unwrap(promotionClient.checkUsable(userId, dto.getUserCouponId()));
            tpl = unwrap(promotionClient.getTemplate(uc.getTemplateId()));
        }
        OrderPrice price = priceEngine.calculate(lines, dto.getEventId(), uc, tpl);

        // ⑥ 扣库存：站票远程 DB 条件更新防超卖；坐票锁座时已在 Redis 原子扣减，支付成功才落 DB sold
        if (!seatOrder) {
            for (Map.Entry<Long, Integer> e : qtyMap.entrySet()) {
                TierInfoDTO et = etMap.get(e.getKey());
                Boolean ok = unwrap(showClient.deductStock(new DeductStockDTO(et.getId(), e.getValue())));
                if (!Boolean.TRUE.equals(ok)) {
                    throw new BusinessException(OrderErrorCode.STOCK_NOT_ENOUGH);
                }
            }
        }

        // ⑥.5 核销券：提供方条件更新防一单多用/并发同用；失败抛异常回滚整单
        if (uc != null) {
            unwrap(promotionClient.markUsed(new MarkUsedDTO(uc.getId(), userId, orderNo)));
        }

        // ⑦ 写订单主表（本地库）
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
            for (SeatInfoDTO es : seatRows) {
                TierInfoDTO et = etMap.get(es.getTierId());
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
                TierInfoDTO et = etMap.get(it.getTierId());
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

        // ⑨ 写状态日志
        saveLog(order.getId(), null, OrderStatus.WAIT_PAY, OrderEvent.USER_CANCEL, "CREATE", userId.toString());

        // ⑩ 清空购物车
        cartService.clear(userId);

        // ⑪ 事务提交后投递延迟关单消息（Redisson 延迟队列 + 定时任务兜底双保险）
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

        // 释放库存：站票远程回补 DB；坐票远程释放 Redis 锁并对齐 DB（锁座时未扣 DB sold）
        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, order.getId()));
        for (OrderItem it : items) {
            if (it.getSeatId() == null) {
                showClient.addStock(new AddStockDTO(it.getEventId(), it.getTierId(), it.getQuantity()));
            }
        }
        order.setStatus(to.name());
        baseMapper.updateById(order);
        saveLog(order.getId(), from, to, OrderEvent.USER_CANCEL, "USER_CANCEL", userId.toString());
        showClient.releaseByOrderNo(orderNo);
        // 取消订单回退券（幂等；降级时由营销服务自行兜底）
        promotionClient.refundByOrderNo(orderNo);
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

        // 站票远程回补 EventTier 库存；坐票由调用方 releaseByOrderNo 释放 Redis 并对齐 DB
        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, o.getId()));
        for (OrderItem it : items) {
            if (it.getSeatId() == null) {
                showClient.addStock(new AddStockDTO(it.getEventId(), it.getTierId(), it.getQuantity()));
            }
        }
        o.setStatus(to.name());
        baseMapper.updateById(o);
        saveLog(o.getId(), from, to, OrderEvent.TIMEOUT_CANCEL, "TIMEOUT", "SYSTEM");
        // 超时关单回退券（幂等）
        promotionClient.refundByOrderNo(orderNo);
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
        // 合并同票档数量，远程查 event_tier 真实价（与下单同口径，防止前端改价）
        Map<Long, Integer> qtyMap = new LinkedHashMap<>();
        for (OrderItemDTO it : dto.getItems()) {
            qtyMap.merge(it.getTierId(), it.getQuantity(), Integer::sum);
        }
        List<PriceLine> lines = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : qtyMap.entrySet()) {
            TierInfoDTO et = unwrap(showClient.getTierInfo(dto.getEventId(), e.getKey()));
            if (et == null) throw new BusinessException(OrderErrorCode.TIER_NOT_ON_SALE);
            lines.add(new PriceLine(e.getKey(), et.getPrice(), e.getValue()));
        }
        UserCouponDTO uc = null;
        CouponTemplateDTO tpl = null;
        if (dto.getUserCouponId() != null) {
            uc = unwrap(promotionClient.checkUsable(userId, dto.getUserCouponId()));
            tpl = unwrap(promotionClient.getTemplate(uc.getTemplateId()));
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
            row.setEventTitle(queryEventTitleQuietly(o.getEventId()));
            row.setPayAmount(o.getPayAmount());
            row.setStatus(o.getStatus());
            row.setCreateTime(o.getCreateTime() == null ? "" : o.getCreateTime().format(fmt));
            rows.add(row);
        }
        return rows;
    }

    /** 第9卷：AI 客服查我的订单（最多20条，按创建时间倒序） */
    @Override
    public List<AiOrderBriefVO> listAiBrief(Long userId, String status) {
        var q = Wrappers.<Orders>lambdaQuery().eq(Orders::getUserId, userId);
        if (status != null && !status.isBlank()) q.eq(Orders::getStatus, status);
        q.orderByDesc(Orders::getCreateTime).last("LIMIT 20");
        return baseMapper.selectList(q).stream().map(o -> {
            AiOrderBriefVO vo = new AiOrderBriefVO();
            vo.setOrderNo(o.getOrderNo());
            vo.setEventTitle(queryEventTitleQuietly(o.getEventId()));
            vo.setStatus(o.getStatus());
            vo.setPayAmount(o.getPayAmount());
            vo.setCreateTime(o.getCreateTime());
            return vo;
        }).toList();
    }

    /** 第9卷：AI 客服查订单详情，复用 getOwnedOrder 强制校验归属 */
    @Override
    public AiOrderDetailVO detailForAi(Long userId, String orderNo) {
        Orders o = getOwnedOrder(userId, orderNo);
        AiOrderDetailVO vo = new AiOrderDetailVO();
        vo.setOrderNo(o.getOrderNo());
        vo.setEventTitle(queryEventTitleQuietly(o.getEventId()));
        vo.setStatus(o.getStatus());
        vo.setTotalAmount(o.getTotalAmount());
        vo.setPayAmount(o.getPayAmount());
        vo.setCreateTime(o.getCreateTime());
        vo.setPayTime(o.getPayTime());

        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, o.getId()));
        vo.setItems(items.stream().map(it -> {
            AiOrderItemVO iv = new AiOrderItemVO();
            iv.setTierName(queryTierNameQuietly(o.getEventId(), it.getTierId()));
            iv.setQuantity(it.getQuantity());
            iv.setSeatId(it.getSeatId());
            return iv;
        }).toList());
        return vo;
    }

    /** 第9卷：AI 客服发起退款（仅 PAID/TICKETED 可申请，复用状态机与日志） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiRefundResultVO applyRefundForAi(Long userId, String orderNo) {
        Orders order = getOwnedOrder(userId, orderNo);
        OrderStatus from = OrderStatus.valueOf(order.getStatus());
        if (from != OrderStatus.PAID && from != OrderStatus.TICKETED) {
            throw new BusinessException(OrderErrorCode.ORDER_STATUS_ILLEGAL.getCode(), "当前订单状态不支持退款");
        }
        OrderStatus to = OrderStateMachine.next(from, OrderEvent.APPLY_REFUND);
        order.setStatus(to.name());
        baseMapper.updateById(order);
        saveLog(order.getId(), from, to, OrderEvent.APPLY_REFUND, "AI_APPLY_REFUND", "AI");

        AiRefundResultVO vo = new AiRefundResultVO();
        vo.setOrderNo(orderNo);
        vo.setStatus(to.name());
        return vo;
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
        vo.setEventTitle(queryEventTitleQuietly(o.getEventId()));
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
            OrderItemVO iv = new OrderItemVO();
            iv.setTierId(it.getTierId());
            iv.setTierName(queryTierNameQuietly(o.getEventId(), it.getTierId()));
            iv.setUnitPrice(it.getUnitPrice());
            iv.setQuantity(it.getQuantity());
            iv.setSubTotal(it.getSubTotal());
            itemVOs.add(iv);
        }
        vo.setItems(itemVOs);
        return vo;
    }

    /** 展示型远程查询：演出服务不可用时降级为空标题/默认名，不打断我的订单页 */
    private String queryEventTitleQuietly(Long eventId) {
        try {
            EventInfoDTO e = unwrap(showClient.getEvent(eventId));
            return e == null ? "" : e.getTitle();
        } catch (RuntimeException ex) {
            log.warn("远程查询场次标题失败 eventId={}: {}", eventId, ex.getMessage());
            return "";
        }
    }

    private String queryTierNameQuietly(Long eventId, Long tierId) {
        try {
            TierInfoDTO t = unwrap(showClient.getTierInfo(eventId, tierId));
            return t == null ? "票档" : t.getTierName();
        } catch (RuntimeException ex) {
            log.warn("远程查询票档名失败 eventId={} tierId={}: {}", eventId, tierId, ex.getMessage());
            return "票档";
        }
    }

    /**
     * 还原远程调用结果：提供方业务异常以 Result(code!=0) 返回（HTTP 200），
     * 这里统一还原成 BusinessException，语义与单体时期一致（可触发本地事务回滚）。
     */
    private <T> T unwrap(Result<T> r) {
        if (r == null) throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        if (r.getCode() != 0) throw new BusinessException(r.getCode(), r.getMessage());
        return r.getData();
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
