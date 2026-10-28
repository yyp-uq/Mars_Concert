package com.mars.order.service;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class OrderPrice {
    private BigDecimal totalAmount;
    private BigDecimal serviceAmount;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;
}
