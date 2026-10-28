package com.mars.common.exception;

import com.mars.common.enums.CommonErrorCode;
import com.mars.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * @RestControllerAdvice = @ControllerAdvice + @ResponseBody
 * 表示：拦截所有 @RestController 抛出的异常，并把返回值写成 JSON。
 * 不同异常走不同 @ExceptionHandler 方法，做到「精准提示，且不把堆栈暴露给用户」。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：可预期的拒绝，原样返回错误码与提示 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** @RequestBody + @Valid 校验失败（JSON 表单） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /** 表单对象绑定校验失败 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /** 单个参数 @RequestParam 上加 @NotBlank 等校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(), e.getMessage());
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(),
                "缺少参数: " + e.getParameterName());
    }

    /** 请求体 JSON 格式错误/为空 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleBody(HttpMessageNotReadableException e) {
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(), "请求体格式错误");
    }

    /** 请求方法不对（接口只接受 POST 却用了 GET） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return Result.fail(CommonErrorCode.PARAM_INVALID.getCode(),
                "不支持的请求方法: " + e.getMethod());
    }

    /** 兜底：所有未预料异常，记录完整堆栈，但只给用户返回友好提示 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.fail(CommonErrorCode.SYSTEM_ERROR);
    }
}
