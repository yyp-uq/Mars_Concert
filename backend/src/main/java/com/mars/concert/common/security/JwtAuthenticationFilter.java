package com.mars.concert.common.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 每个请求只执行一次(OncePerRequestFilter)。
 * 职责：解析 token → 校验 Redis 登录态 → 填充 SecurityContext。
 * 不在这里直接返回 401，交给后续授权层/统一异常；无 token 直接放行（由 authorizeHttpRequests 决定是否拦截）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final SecurityUserLoader userLoader;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtUtil.parse(token);
                String type = claims.get("type", String.class);
                if ("access".equals(type)) {
                    Long userId = Long.valueOf(claims.getSubject());
                    String jti = claims.getId();
                    String device = claims.get("device", String.class);

                    // 校验 Redis 登录态存在且 jti 匹配（实现踢人/单点控制）
                    String redisKey = "mars:token:access:" + userId + ":" + device;
                    Object cached = redisTemplate.opsForValue().get(redisKey);
                    if (cached != null && jti.equals(cached.toString())) {
                        LoginUser loginUser = userLoader.load(userId);
                        if (loginUser != null) {
                            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                            loginUser.getPermissions().forEach(p ->
                                    authorities.add(new SimpleGrantedAuthority(p)));
                            loginUser.getRoles().forEach(r ->
                                    authorities.add(new SimpleGrantedAuthority(r)));

                            UsernamePasswordAuthenticationToken auth =
                                    new UsernamePasswordAuthenticationToken(
                                            loginUser, null, authorities);
                            SecurityContextHolder.getContext().setAuthentication(auth);
                            UserContext.set(loginUser);
                        }
                    }
                }
            } catch (Exception e) {
                // token 过期/篡改：不抛 500，视为未登录，交给授权层返回 401
                log.debug("JWT 校验失败: {}", e.getMessage());
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // 请求结束清理 ThreadLocal，防止线程复用导致用户串号
            UserContext.clear();
        }
    }
}
