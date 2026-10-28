package com.mars.common.web;

import com.mars.common.security.LoginUser;
import com.mars.common.security.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 鉴权下推（第8卷 §7）：网关在全局过滤器里完成 JWT 校验后，
 * 把用户身份以 X-User-Id 请求头透传给内部服务。
 * 内部服务默认信任该头（网络层只允许网关访问），这里把它还原进 UserContext，
 * @CurrentUser 取值逻辑与单体时期完全一致，业务代码零改动。
 */
@Component
public class HeaderUserFilter extends OncePerRequestFilter {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uid = request.getHeader(HEADER_USER_ID);
        try {
            if (uid != null && !uid.isBlank()) {
                // 权限集合由 auth-service 登录态维护，内部服务仅需 userId，给空集合占位
                UserContext.set(new LoginUser(Long.valueOf(uid), Set.of(), Set.of()));
            }
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();   // 防止线程池复用串号
        }
    }
}
