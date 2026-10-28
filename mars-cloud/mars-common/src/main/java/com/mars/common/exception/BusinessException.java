package com.mars.common.exception;

import com.mars.common.enums.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：业务规则不满足时抛出（如 FAQ 不存在）。
 * 它不是「程序出错」，而是「正常的业务拒绝」，所以用受检外的运行时异常，
 * 由全局异常处理器统一捕获并转成友好 JSON，而不是返回 500 白页。
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
