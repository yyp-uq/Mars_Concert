package com.mars.concert.order.statemachine;

import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.order.enums.OrderErrorCode;
import com.mars.concert.order.enums.OrderEvent;
import com.mars.concert.order.enums.OrderStatus;

import java.util.EnumMap;
import java.util.Map;

/**
 * 用「状态 × 事件 → 目标状态」的迁移表实现状态机。
 * 非法迁移直接抛业务异常。
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Map<OrderEvent, OrderStatus>> MACHINE = new EnumMap<>(OrderStatus.class);

    static {
        put(OrderStatus.WAIT_PAY, OrderEvent.USER_CANCEL, OrderStatus.CANCELLED);
        put(OrderStatus.WAIT_PAY, OrderEvent.TIMEOUT_CANCEL, OrderStatus.CANCELLED);
        put(OrderStatus.WAIT_PAY, OrderEvent.PAY_SUCCESS, OrderStatus.PAID);

        put(OrderStatus.PAID, OrderEvent.TICKET_SUCCESS, OrderStatus.TICKETED);
        put(OrderStatus.PAID, OrderEvent.APPLY_REFUND, OrderStatus.REFUNDING);

        put(OrderStatus.TICKETED, OrderEvent.APPLY_REFUND, OrderStatus.REFUNDING);
        put(OrderStatus.TICKETED, OrderEvent.EVENT_FINISH, OrderStatus.FINISHED);

        put(OrderStatus.REFUNDING, OrderEvent.REFUND_SUCCESS, OrderStatus.REFUNDED);
        put(OrderStatus.REFUNDING, OrderEvent.REFUND_REJECT, OrderStatus.PAID);
    }

    private static void put(OrderStatus s, OrderEvent e, OrderStatus t) {
        MACHINE.computeIfAbsent(s, k -> new EnumMap<>(OrderEvent.class)).put(e, t);
    }

    /** 返回迁移后的状态；非法则抛异常 */
    public static OrderStatus next(OrderStatus current, OrderEvent event) {
        Map<OrderEvent, OrderStatus> map = MACHINE.get(current);
        OrderStatus target = map == null ? null : map.get(event);
        if (target == null) {
            throw new BusinessException(OrderErrorCode.ORDER_STATUS_ILLEGAL);
        }
        return target;
    }
}
