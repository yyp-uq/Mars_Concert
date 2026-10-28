package com.mars.concert.content.enums;

import com.mars.concert.common.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ContentErrorCode implements ErrorCode {
    FAQ_NOT_FOUND(6001, "FAQ不存在或已下架");

    private final int code;
    private final String message;
}
