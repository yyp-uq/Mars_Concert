package com.mars.concert.show.model.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TierStockVO {
    private Long tierId;
    private String name;
    private BigDecimal price;
    private Integer remainStock;
}
