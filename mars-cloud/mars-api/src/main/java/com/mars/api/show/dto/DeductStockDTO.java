package com.mars.api.show.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 站票扣库存请求：按 show_event_tier.id 条件更新防超卖 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeductStockDTO {
    /** show_event_tier.id */
    private Long id;
    private Integer quantity;
}
