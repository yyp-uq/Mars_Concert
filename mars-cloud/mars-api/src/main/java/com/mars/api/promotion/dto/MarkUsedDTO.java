package com.mars.api.promotion.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 核销券请求：条件更新防一单多用/并发同用 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarkUsedDTO {
    private Long id;
    private Long userId;
    private String orderNo;
}
