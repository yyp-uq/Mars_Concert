package com.mars.order.service;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class PriceLine {
    private Long tierId;
    private BigDecimal price;
    private int quantity;
}
