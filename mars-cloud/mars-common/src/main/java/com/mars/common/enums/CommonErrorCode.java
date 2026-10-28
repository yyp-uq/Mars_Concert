package com.mars.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码分段约定：
 * 0 成功；1xxx 通用/参数；2xxx 会员；3xxx 演出；4xxx 订单；5xxx 支付……
 */
@Getter
@AllArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    PARAM_INVALID(1001, "参数不合法"),
    RESOURCE_NOT_FOUND(1002, "资源不存在"),
    SYSTEM_ERROR(9999, "系统繁忙，请稍后再试"),
    UNAUTHORIZED(1401, "未登录或登录已过期"),
    FORBIDDEN(1403, "没有操作权限");

    private final int code;
    private final String message;
}
