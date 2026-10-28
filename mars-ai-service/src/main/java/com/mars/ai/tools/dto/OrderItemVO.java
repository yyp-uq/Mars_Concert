package com.mars.ai.tools.dto;

/** 订单明细行 */
public record OrderItemVO(String tierName, Integer quantity, Long seatId) {
}
