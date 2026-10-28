package com.mars.common.web;

import java.lang.annotation.*;

/** 标注在 Controller 方法参数上，自动注入当前登录用户 id 或 LoginUser */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
    /** required=false 时游客访问返回 null 而不抛 401（如座位图查询） */
    boolean required() default true;
}
