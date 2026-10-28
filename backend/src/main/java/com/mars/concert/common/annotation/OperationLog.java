package com.mars.concert.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 操作日志注解（手册 §11.1）：打在关键写操作上，AOP 异步落 sys_operation_log */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {
    /** 模块 */
    String module();

    /** 动作 */
    String action();

    /** SpEL 取业务id，如 "#orderNo" */
    String bizIdSpel() default "";
}
