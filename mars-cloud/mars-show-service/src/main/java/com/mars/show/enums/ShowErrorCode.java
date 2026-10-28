package com.mars.show.enums;

import com.mars.common.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 演出域错误码（3xxx 段，与单体时期 OrderErrorCode 中演出相关项保持同码，前端无感）。
 */
@Getter
@AllArgsConstructor
public enum ShowErrorCode implements ErrorCode {
    SEAT_TAKEN(3001, "座位已被选中"),
    EVENT_BUSY(3002, "系统繁忙，请重试");

    private final int code;
    private final String message;
}
