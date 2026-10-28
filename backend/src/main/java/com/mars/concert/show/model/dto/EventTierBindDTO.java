package com.mars.concert.show.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class EventTierBindDTO {
    @NotNull
    private Long tierId;
    @NotNull
    private BigDecimal price;
    @NotNull @Min(0)
    private Integer totalStock;
}
