package com.mars.api.show.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 场次状态码（服务间共享的常量口径，与 show-service 内部 EventStatus 保持一致）。
 * 调用方（如 order-service）用它判断场次是否可售，避免直接依赖提供方实体。
 */
@Getter
@AllArgsConstructor
public enum EventStatusEnum {
    DRAFT(0), PRESALE(1), ONSALE(2), SOLD_OUT(3), FINISHED(4), CANCELLED(5);
    private final int code;
}
