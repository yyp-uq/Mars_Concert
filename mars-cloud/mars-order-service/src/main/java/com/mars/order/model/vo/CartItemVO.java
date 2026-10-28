package com.mars.order.model.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CartItemVO {
    private Long eventId;
    private Long tierId;
    private String tierName;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal subTotal;
}
