package com.mars.concert.common.enums;

/**
 * 错误码规范：让每个业务模块实现自己的错误码枚举。
 * 本卷先定义接口 + 一个通用枚举。
 */
public interface ErrorCode {
    int getCode();
    String getMessage();
}
