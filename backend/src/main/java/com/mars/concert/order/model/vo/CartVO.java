package com.mars.concert.order.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CartVO {
    private List<CartItemVO> items;
    private BigDecimal total;
}
