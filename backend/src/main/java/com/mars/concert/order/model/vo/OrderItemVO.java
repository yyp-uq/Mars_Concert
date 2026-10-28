package com.mars.concert.order.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderItemVO {
    private Long tierId;
    private String tierName;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal subTotal;
}
