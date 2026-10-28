package com.mars.common.security;

/**
 * 基于 ThreadLocal 的当前用户容器。
 * 一次请求一个线程，过滤器 set，业务 get，请求结束必须 remove 防止线程池复用串号。
 */
public class UserContext {
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    public static void set(LoginUser user) { HOLDER.set(user); }
    public static LoginUser get() { return HOLDER.get(); }
    public static Long getUserId() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getUserId();
    }
    public static void clear() { HOLDER.remove(); }
}
