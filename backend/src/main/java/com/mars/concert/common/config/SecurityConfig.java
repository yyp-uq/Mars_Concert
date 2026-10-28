package com.mars.concert.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.concert.common.result.Result;
import com.mars.concert.common.enums.CommonErrorCode;
import com.mars.concert.common.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity   // 开启 @PreAuthorize 方法级鉴权（Spring Security6 用此注解替代 @EnableGlobalMethodSecurity）
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 密码编码器：BCrypt 自带随机盐，校验时用 matches(明文,密文) */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 前后端分离、无 Cookie 会话，关闭 CSRF
            .csrf(AbstractHttpConfigurer::disable)
            // 不创建 HttpSession，完全靠 token
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 白名单：静态资源、文档、登录注册、FAQ查询(C端可读)
                .requestMatchers(
                        "/", "/index.html", "/pages/**", "/views/**", "/css/**", "/js/**",
                        "/assets/**", "/fonts/**", "/favicon.svg",
                        "/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-ui/**",
                        "/swagger-resources/**",
                        "/api/v1/ping",
                        "/api/v1/auth/**",          // 登录注册验证码
                        "/api/v1/faqs",             // GET 列表公开（下面单独限制写操作）
                        "/api/v1/pay/callback/**",  // 第5卷：渠道服务器回调，无用户token
                        "/mock/**",                 // 第5卷：模拟支付收银台静态页
                        "/ws/**"                    // 第6卷：WebSocket 握手（token 走 query，握手拦截器自验，游客可看弹幕）
                ).permitAll()
                // 第6卷：歌曲搜索/热词榜公开（C端游客可搜）
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/search/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/faqs/**").permitAll()
                // 第5卷：收银台读取支付单金额（模拟页无 token）
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/payments/*/info").permitAll()
                // C 端公开查询：场次列表/详情/首页聚合
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/events").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/events/home").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/events/{id}").permitAll()
                // C 端公开查询：座位图（游客可看，登录后标 mine）
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/events/*/seats").permitAll()
                // 后台接口需 ADMIN
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // 其余一律要登录
                .anyRequest().authenticated()
            )
            // 把 JWT 过滤器插到表单认证过滤器之前
            .addFilterBefore(jwtAuthenticationFilter,
                    UsernamePasswordAuthenticationFilter.class)
            // 统一 401/403 JSON 输出（前端 axios/fetch 按 HTTP 状态处理）
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, resp, e) -> writeError(resp, 401, CommonErrorCode.UNAUTHORIZED))
                .accessDeniedHandler((req, resp, e) -> writeError(resp, 403, CommonErrorCode.FORBIDDEN))
            );

        return http.build();
    }

    private void writeError(HttpServletResponse resp, int httpStatus, CommonErrorCode code) throws java.io.IOException {
        resp.setStatus(httpStatus);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(objectMapper.writeValueAsString(Result.fail(code)));
    }
}
