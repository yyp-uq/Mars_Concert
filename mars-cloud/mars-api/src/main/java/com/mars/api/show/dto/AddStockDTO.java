package com.mars.api.show.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 站票回补库存请求（取消/超时关单），按 eventId + tierId 定位 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddStockDTO {
    private Long eventId;
    private Long tierId;
    private Integer quantity;
}
