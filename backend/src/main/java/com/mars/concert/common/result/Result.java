package com.mars.concert.common.result;

import com.mars.concert.common.enums.ErrorCode;
import lombok.Data;

/**
 * 全项目统一响应结构。
 * 前端只需要判断 code==0 即成功，失败时读 message。
 * { "code":0, "message":"success", "data":... }
 */
@Data
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    // 成功（带数据）
    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    // 成功（无数据，如删除）
    public static <T> Result<T> success() {
        return success(null);
    }

    // 失败（用错误码枚举）
    public static <T> Result<T> fail(ErrorCode errorCode) {
        Result<T> r = new Result<>();
        r.code = errorCode.getCode();
        r.message = errorCode.getMessage();
        return r;
    }

    // 失败（自定义信息，用于参数校验等）
    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
