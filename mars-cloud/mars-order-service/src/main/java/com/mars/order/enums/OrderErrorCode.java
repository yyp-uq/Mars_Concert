package com.mars.order.enums;

import com.mars.common.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    EVENT_NOT_SALE(4001, "该场次当前不可购票"),
    TIER_NOT_ON_SALE(4002, "该票档本场次未开售"),
    STOCK_NOT_ENOUGH(4003, "余票不足"),
    OVER_LIMIT(4004, "超出每单限购数量"),
    ORDER_NOT_FOUND(4005, "订单不存在"),
    ORDER_STATUS_ILLEGAL(4006, "订单状态不允许该操作"),
    CART_EMPTY(4007, "购物车为空"),
    REPEAT_SUBMIT(4008, "请勿重复提交订单"),
    SEAT_TAKEN(3001, "座位已被选中"),
    EVENT_BUSY(3002, "系统繁忙，请重试");

    private final int code;
    private final String message;
}
