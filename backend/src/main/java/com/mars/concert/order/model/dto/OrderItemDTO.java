package com.mars.concert.order.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OrderItemDTO {
    @NotNull
    private Long tierId;
    @NotNull @Min(value = 1, message = "数量至少1")
    private Integer quantity;
    private Long viewerId;
}
