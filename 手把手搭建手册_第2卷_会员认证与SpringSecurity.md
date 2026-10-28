# 手把手搭建手册（第 2 卷）
## 会员认证：Spring Security 6 + JWT 双 Token + Redis 会话 + RBAC 权限

> 前置：已完成第 1 卷（工程能启动、FAQ CRUD 跑通）。
> 本卷结束时你将得到：手机号+验证码登录、密码登录、注册、JWT 双 Token（access/refresh）、Redis 登录态与踢人下线、`@CurrentUser` 注入当前用户、基于角色权限点的 `@PreAuthorize` 接口鉴权，以及前端 `api.js` 自动带 Token、401 无感刷新。
> 包根仍为 `com.mars.concert`。新增业务包：`member`（会员域）、`security`（认证基础设施，放 common 下）。

---

## 目录
1. [本卷架构与请求链路](#1-本卷架构与请求链路)
2. [准备 Redis](#2-准备-redis)
3. [建表 SQL](#3-建表-sql)
4. [新增依赖与配置](#4-新增依赖与配置)
5. [Redis 配置类](#5-redis-配置类)
6. [JWT 工具类](#6-jwt-工具类)
7. [会员实体与 Mapper](#7-会员实体与-mapper)
8. [登录上下文 UserContext 与 @CurrentUser](#8-登录上下文-usercontext-与-currentuser)
9. [UserDetailsService 与认证主体](#9-userdetailsservice-与认证主体)
10. [JWT 认证过滤器](#10-jwt-认证过滤器)
11. [Spring Security 6 过滤器链配置（核心）](#11-spring-security-6-过滤器链配置核心)
12. [认证 DTO/VO](#12-认证-dtovo)
13. [验证码与登录 Service](#13-验证码与登录-service)
14. [Auth Controller（处理 HTTP）](#14-auth-controller处理-http)
15. [权限加载与 @PreAuthorize](#15-权限加载与-preauthorize)
16. [观演人模块（带数据权限示例）](#16-观演人模块带数据权限示例)
17. [前端 api.js 改造与登录页](#17-前端-apijs-改造与登录页)
18. [测试与排错](#18-测试与排错)

---

## 1. 本卷架构与请求链路

为什么用「JWT + Redis」而不是只用 JWT？
- 纯 JWT 无状态，签发后在过期前无法主动作废（用户改密码、被封号、退出登录后旧 token 仍有效）。
- 方案：JWT 携带身份，Redis 存「允许访问的登录态」。过滤器验签后还要查 Redis 是否存在 → 可随时删除 Redis key 实现踢人下线。
- 双 Token：access（短期 2 小时，放请求头）+ refresh（长期 7 天，仅用于换新 access）。access 泄露风险窗口小。

受保护请求链路：
```
请求带 Authorization: Bearer <accessToken>
 → JwtAuthenticationFilter（在 Security 过滤器链中）
   → 验签、解析 userId/jti
   → 查 Redis mars:token:access:{userId}:{device} 是否存在且 jti 匹配
   → 组装 LoginUser(userId,权限集合) 放入 SecurityContext + UserContext(ThreadLocal)
 → @PreAuthorize 校验权限点
 → Controller，可用 @CurrentUser Long userId 直接拿用户id
```

---

## 2. 准备 Redis

任选一种：

**Docker（推荐）**
```powershell
docker run --name mars-redis -p 6379:6379 -d redis:7 redis-server --appendonly yes
```

**Windows 原生**：用 Memurai 或 WSL，或下载社区维护的 Redis for Windows zip 启动 `redis-server.exe`。

验证：`docker exec -it mars-redis redis-cli ping` 返回 `PONG`。

IDEA Ultimate 可在 Database 面板 `+ → Data Source → Redis` 连接，直接查看 key。

---

## 3. 建表 SQL

在 `mars_concert` 库执行：

```sql
-- 用户表
CREATE TABLE uc_user (
  id BIGINT NOT NULL AUTO_INCREMENT,
  phone VARCHAR(32) NOT NULL COMMENT '手机号(加密或明文,学习期先明文)',
  password VARCHAR(100) DEFAULT NULL COMMENT 'BCrypt密文',
  nickname VARCHAR(64) DEFAULT NULL,
  avatar VARCHAR(255) DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 0冻结 2注销',
  last_login_time DATETIME DEFAULT NULL,
  last_login_ip VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), UNIQUE KEY uk_phone(phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员用户';

-- 角色
CREATE TABLE uc_role (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT '角色码 如 ROLE_MEMBER/ROLE_ADMIN',
  name VARCHAR(64) NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_code(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 权限点
CREATE TABLE uc_permission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(128) NOT NULL COMMENT '权限点 如 content:faq:edit',
  name VARCHAR(128) NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_code(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE uc_user_role (
  user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
  PRIMARY KEY(user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE uc_role_permission (
  role_id BIGINT NOT NULL, permission_id BIGINT NOT NULL,
  PRIMARY KEY(role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 实名观演人
CREATE TABLE uc_viewer (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  id_card VARCHAR(32) NOT NULL COMMENT '学习期明文,生产加密',
  phone VARCHAR(32) DEFAULT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), KEY idx_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观演人';

-- 初始化角色与权限
INSERT INTO uc_role(id,code,name) VALUES (1,'ROLE_MEMBER','会员'),(2,'ROLE_ADMIN','管理员');
INSERT INTO uc_permission(id,code,name) VALUES
 (1,'content:faq:edit','编辑FAQ'),(2,'order:refund:audit','退款审核'),(3,'show:event:edit','编辑场次');
INSERT INTO uc_role_permission(role_id,permission_id) VALUES (2,1),(2,2),(2,3);
```

> 权限点命名规范 `模块:资源:动作`，方法上用同样字符串做 `@PreAuthorize`，前后端一致。

---

## 4. 新增依赖与配置

`pom.xml` 的 `<dependencies>` 内加入：

```xml
<!-- 安全 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<!-- Redis -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<!-- Redisson：分布式锁/延迟队列，本卷先用于登录态与分布式能力 -->
<dependency>
  <groupId>org.redisson</groupId>
  <artifactId>redisson-spring-boot-starter</artifactId>
  <version>3.34.1</version>
</dependency>
<!-- JWT(jjwt 0.12.x，API 与旧版不同，注意) -->
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-api</artifactId>
  <version>0.12.6</version>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-impl</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-jackson</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>
```

`application.yml` 追加：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 0

mars:
  jwt:
    # 生产用环境变量注入，至少32字节(HS256)。学习期写这里
    secret: mars-concert-super-secret-key-please-change-in-prod-0123456789
    access-ttl-seconds: 7200      # 2小时
    refresh-ttl-seconds: 604800   # 7天
```

Reload Maven。

---

## 5. Redis 配置类

路径：`common/config/RedisConfig.java`

```java
package com.mars.concert.common.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * 自定义 RedisTemplate：
     * key 用 String 序列化（可读），value 用 JSON 序列化（对象可直接存取）。
     * 默认 JdkSerializable 会产生乱码字节，必须替换。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, Object> tpl = new RedisTemplate<>();
        tpl.setConnectionFactory(cf);

        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());          // 支持 LocalDateTime
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);    // 保留类型信息，反序列化成原对象
        GenericJackson2JsonRedisSerializer json = new GenericJackson2JsonRedisSerializer(om);

        tpl.setKeySerializer(new StringRedisSerializer());
        tpl.setHashKeySerializer(new StringRedisSerializer());
        tpl.setValueSerializer(json);
        tpl.setHashValueSerializer(json);
        tpl.afterPropertiesSet();
        return tpl;
    }
}
```

再封装一个常用 Redis 工具（路径 `common/utils/RedisService.java`）：

```java
package com.mars.concert.common.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RedisService {
    private final RedisTemplate<String, Object> redisTemplate;

    public void set(String key, Object value, long ttlSeconds) {
        redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }
    public Object get(String key) { return redisTemplate.opsForValue().get(key); }
    public Boolean delete(String key) { return redisTemplate.delete(key); }
    public Boolean exists(String key) { return redisTemplate.hasKey(key); }
    public Long incr(String key) { return redisTemplate.opsForValue().increment(key); }
    public void expire(String key, long ttlSeconds) {
        redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
    }
}
```

---

## 6. JWT 工具类

路径：`common/security/JwtProperties.java`

```java
package com.mars.concert.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mars.jwt")
public class JwtProperties {
    private String secret;
    private Long accessTtlSeconds;
    private Long refreshTtlSeconds;
}
```

路径：`common/security/JwtUtil.java`

```java
package com.mars.concert.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 生成/解析。jjwt 0.12.x API。
 * 载荷：sub=userId, jti=唯一id(用于Redis登录态匹配), type=access/refresh
 */
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties props;
    private SecretKey key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId, String device) {
        return build(userId, device, "access", props.getAccessTtlSeconds());
    }

    public String createRefreshToken(Long userId, String device) {
        return build(userId, device, "refresh", props.getRefreshTtlSeconds());
    }

    private String build(Long userId, String device, String type, long ttl) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", type);
        claims.put("device", device);
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())   // jti
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl * 1000))
                .signingKey(key)
                .compact();
    }

    /** 解析，验签失败/过期会抛 JwtException，由调用方捕获 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
```

> `@ConfigurationProperties` 把 yml 里 `mars.jwt.*` 绑定成对象，比散落的 `@Value` 更集中、可复用。

---

## 7. 会员实体与 Mapper

路径：`member/entity/User.java`

```java
package com.mars.concert.member.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("uc_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String password;
    private String nickname;
    private String avatar;
    private Integer status;          // 1正常 0冻结
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
    @Version
    private Integer version;
}
```

路径：`member/mapper/UserMapper.java`

```java
package com.mars.concert.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.concert.member.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 查用户拥有的权限点 code（关联5张表） */
    @Select("""
        SELECT DISTINCT p.code
        FROM uc_user_role ur
        JOIN uc_role_permission rp ON rp.role_id = ur.role_id
        JOIN uc_permission p ON p.id = rp.permission_id
        WHERE ur.user_id = #{userId}
        """)
    List<String> selectPermissionCodes(@Param("userId") Long userId);

    /** 查用户角色 code */
    @Select("""
        SELECT r.code FROM uc_user_role ur
        JOIN uc_role r ON r.id = ur.role_id
        WHERE ur.user_id = #{userId}
        """)
    List<String> selectRoleCodes(@Param("userId") Long userId);
}
```

> 用 `@Select` + 文本块(`"""`)写简单多表关联；复杂 SQL 再用 XML。

---

## 8. 登录上下文 UserContext 与 @CurrentUser

路径：`common/security/LoginUser.java`

```java
package com.mars.concert.common.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.Set;

/** 登录态主体（认证成功后在请求内传递） */
@Data
@AllArgsConstructor
public class LoginUser {
    private Long userId;
    private Set<String> permissions;
    private Set<String> roles;
}
```

路径：`common/security/UserContext.java`

```java
package com.mars.concert.common.security;

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
```

路径：`common/web/CurrentUser.java`（新建 `common/web` 包）

```java
package com.mars.concert.common.web;

import java.lang.annotation.*;

/** 标注在 Controller 方法参数上，自动注入当前登录用户 id 或 LoginUser */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {}
```

路径：`common/web/CurrentUserArgumentResolver.java`

```java
package com.mars.concert.common.web;

import com.mars.concert.common.enums.CommonErrorCode;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.common.security.LoginUser;
import com.mars.concert.common.security.UserContext;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter p, ModelAndViewContainer m,
                                  NativeWebRequest w, WebDataBinderFactory b) {
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        // 参数类型是 Long 就返回 userId，否则返回整个 LoginUser
        if (Long.class.equals(p.getParameterType())) {
            return user.getUserId();
        }
        return user;
    }
}
```

把它注册到 MVC（修改第 1 卷的 `WebMvcConfig`）：

```java
// 注入
private final CurrentUserArgumentResolver currentUserArgumentResolver;
// 构造器注入（或用 @RequiredArgsConstructor）

@Override
public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(currentUserArgumentResolver);
}
```

---

## 9. UserDetailsService 与认证主体

本项目自定义 JWT 过滤器，不强依赖表单登录，但为了与 Spring Security 权限体系打通，提供一个查询用户+权限的服务。路径：`common/security/SecurityUserLoader.java`

```java
package com.mars.concert.common.security;

import com.mars.concert.member.entity.User;
import com.mars.concert.member.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SecurityUserLoader {

    private final UserMapper userMapper;

    /** 登录认证过滤器调用：组装含权限的 LoginUser */
    public LoginUser load(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != 1) {
            return null;   // 用户不存在/冻结
        }
        List<String> perms = userMapper.selectPermissionCodes(userId);
        List<String> roles = userMapper.selectRoleCodes(userId);
        return new LoginUser(userId, new HashSet<>(perms), new HashSet<>(roles));
    }

    public User findByPhone(String phone) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, phone));
    }
}
```

---

## 10. JWT 认证过滤器

路径：`common/security/JwtAuthenticationFilter.java`

```java
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
```

> 权限同时放角色码 `ROLE_ADMIN`（hasRole 用）和权限点 `content:faq:edit`（hasAuthority 用）到 authorities，`@PreAuthorize("hasAuthority('content:faq:edit')")` 即生效。

---

## 11. Spring Security 6 过滤器链配置（核心）

路径：`common/config/SecurityConfig.java`

```java
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
                        "/", "/index.html", "/pages/**", "/css/**", "/js/**",
                        "/assets/**", "/fonts/**", "/favicon.svg",
                        "/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-ui/**",
                        "/swagger-resources/**",
                        "/api/v1/ping",
                        "/api/v1/auth/**",          // 登录注册验证码
                        "/api/v1/faqs"              // GET 列表公开（下面单独限制写操作）
                ).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/faqs/**").permitAll()
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
```

关键点（为什么）：
- Spring Security 6 用 `SecurityFilterChain` Bean 配置，**不再继承** `WebSecurityConfigurerAdapter`（已删除）。
- `@EnableMethodSecurity` 才能让 `@PreAuthorize` 生效。
- 无状态 JWT 必须 `STATELESS`，否则框架还会创建 session。
- 401/403 用自定义入口写 JSON，保证全站响应结构一致，前端不会收到 HTML 错误页。

---

## 12. 认证 DTO/VO

路径：`member/model/dto/SmsLoginDTO.java`

```java
package com.mars.concert.member.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SmsLoginDTO {
    @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    private String code;

    private String device;   // web/app，用于多端登录态 key
}
```

`PasswordLoginDTO.java`（phone + password，同样校验）、`RegisterDTO`（phone、code、password，加 `@Size(min=6,max=20)`）、`RefreshDTO`（refreshToken）。

路径：`member/model/vo/LoginVO.java`

```java
package com.mars.concert.member.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginVO {
    private String accessToken;
    private String refreshToken;
    private Long userId;
    private String nickname;
    private String avatar;
}
```

---

## 13. 验证码与登录 Service

新增 Redis key 常量（路径 `common/constant/RedisKeys.java`）：

```java
package com.mars.concert.common.constant;

public interface RedisKeys {
    String SMS_CODE = "mars:sms:";             // +phone
    String SMS_DAY_CNT = "mars:sms:cnt:";      // +phone
    String LOGIN_FAIL = "mars:login:fail:";    // +phone
    String TOKEN_ACCESS = "mars:token:access:";
    String TOKEN_REFRESH = "mars:token:refresh:";
}
```

路径：`member/service/AuthService.java`

```java
package com.mars.concert.member.service;

import com.mars.concert.member.model.dto.*;
import com.mars.concert.member.model.vo.LoginVO;

public interface AuthService {
    void sendSmsCode(String phone);
    LoginVO smsLogin(SmsLoginDTO dto, String ip);
    LoginVO passwordLogin(PasswordLoginDTO dto, String ip);
    void register(RegisterDTO dto);
    LoginVO refresh(String refreshToken);
    void logout(String device);
}
```

路径：`member/service/impl/AuthServiceImpl.java`

```java
package com.mars.concert.member.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.common.enums.CommonErrorCode;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.common.security.*;
import com.mars.concert.common.utils.RedisService;
import com.mars.concert.member.entity.User;
import com.mars.concert.member.mapper.UserMapper;
import com.mars.concert.member.model.dto.*;
import com.mars.concert.member.model.vo.LoginVO;
import com.mars.concert.member.service.AuthService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final RedisService redis;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProps;
    private final PasswordEncoder passwordEncoder;
    private final SecurityUserLoader userLoader;

    @Override
    public void sendSmsCode(String phone) {
        // 频控：60秒1次
        if (Boolean.TRUE.equals(redis.exists(RedisKeys.SMS_CODE + phone))) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(), "验证码发送过于频繁");
        }
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        // 学习期：不接短信平台，打印到控制台模拟发送
        log.info("【火星演唱会】手机号 {} 的登录验证码为：{}（5分钟内有效）", phone, code);
        redis.set(RedisKeys.SMS_CODE + phone, code, 300);
    }

    @Override
    @Transactional
    public LoginVO smsLogin(SmsLoginDTO dto, String ip) {
        verifyCode(dto.getPhone(), dto.getCode());
        User user = userLoader.findByPhone(dto.getPhone());
        if (user == null) {
            // 验证码登录即自动注册
            user = new User();
            user.setPhone(dto.getPhone());
            user.setNickname("火星人" + dto.getPhone().substring(7));
            user.setStatus(1);
            userMapper.insert(user);
            // 分配默认会员角色（需补 uc_user_role 插入；学习期可给固定 role_id=1）
            userMapper.insertUserRole(user.getId(), 1L);
        }
        checkNormal(user);
        return issueTokens(user, dto.getDevice(), ip);
    }

    @Override
    public LoginVO passwordLogin(PasswordLoginDTO dto, String ip) {
        User user = userLoader.findByPhone(dto.getPhone());
        if (user == null || user.getPassword() == null
                || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(), "手机号或密码错误");
        }
        checkNormal(user);
        return issueTokens(user, dto.getDevice(), ip);
    }

    @Override
    public void register(RegisterDTO dto) {
        verifyCode(dto.getPhone(), dto.getCode());
        if (userLoader.findByPhone(dto.getPhone()) != null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(), "该手机号已注册");
        }
        User u = new User();
        u.setPhone(dto.getPhone());
        u.setPassword(passwordEncoder.encode(dto.getPassword()));
        u.setNickname("火星人" + dto.getPhone().substring(7));
        u.setStatus(1);
        userMapper.insert(u);
        userMapper.insertUserRole(u.getId(), 1L);
    }

    @Override
    public LoginVO refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtUtil.parse(refreshToken);
        } catch (Exception e) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        if (!"refresh".equals(claims.get("type", String.class))) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        Long userId = Long.valueOf(claims.getSubject());
        String device = claims.get("device", String.class);
        String key = RedisKeys.TOKEN_REFRESH + userId + ":" + device;
        Object cached = redis.get(key);
        if (cached == null || !claims.getId().equals(cached.toString())) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED.getCode(), "登录已失效，请重新登录");
        }
        User user = userMapper.selectById(userId);
        checkNormal(user);
        // refresh 旋转：签发新对 token，旧 refresh 作废，防重放
        return issueTokens(user, device, null);
    }

    @Override
    public void logout(String device) {
        Long userId = UserContext.getUserId();
        if (userId == null) return;
        redis.delete(RedisKeys.TOKEN_ACCESS + userId + ":" + device);
        redis.delete(RedisKeys.TOKEN_REFRESH + userId + ":" + device);
    }

    /* ---------------- 私有方法 ---------------- */

    private void verifyCode(String phone, String input) {
        Object saved = redis.get(RedisKeys.SMS_CODE + phone);
        if (saved == null || !saved.toString().equals(input)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(), "验证码错误或已过期");
        }
        redis.delete(RedisKeys.SMS_CODE + phone);   // 一次性
    }

    private void checkNormal(User user) {
        if (user.getStatus() != 1) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN.getCode(), "账号已被冻结或注销");
        }
    }

    private LoginVO issueTokens(User user, String device, String ip) {
        device = (device == null || device.isBlank()) ? "web" : device;
        String access = jwtUtil.createAccessToken(user.getId(), device);
        String refresh = jwtUtil.createRefreshToken(user.getId(), device);

        Claims a = jwtUtil.parse(access);
        Claims r = jwtUtil.parse(refresh);
        redis.set(RedisKeys.TOKEN_ACCESS + user.getId() + ":" + device, a.getId(), jwtProps.getAccessTtlSeconds());
        redis.set(RedisKeys.TOKEN_REFRESH + user.getId() + ":" + device, r.getId(), jwtProps.getRefreshTtlSeconds());

        // 更新登录信息
        User up = new User();
        up.setId(user.getId());
        up.setLastLoginTime(LocalDateTime.now());
        up.setLastLoginIp(ip);
        userMapper.updateById(up);

        return LoginVO.builder()
                .accessToken(access).refreshToken(refresh)
                .userId(user.getId()).nickname(user.getNickname())
                .avatar(user.getAvatar()).build();
    }
}
```

在 `UserMapper` 补一个方法（或用 XML）：

```java
@Insert("INSERT INTO uc_user_role(user_id, role_id) VALUES(#{userId},#{roleId})")
void insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
```
（记得 import `org.apache.ibatis.annotations.Insert`。）

---

## 14. Auth Controller（处理 HTTP）

路径：`member/controller/AuthController.java`

```java
package com.mars.concert.member.controller;

import com.mars.concert.common.result.Result;
import com.mars.concert.common.security.UserContext;
import com.mars.concert.member.model.dto.*;
import com.mars.concert.member.model.vo.LoginVO;
import com.mars.concert.member.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "会员-认证")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "发送短信验证码")
    @PostMapping("/sms/send")
    public Result<Void> sendSms(@RequestParam String phone) {
        authService.sendSmsCode(phone);
        return Result.success();
    }

    @Operation(summary = "验证码登录/自动注册")
    @PostMapping("/login/sms")
    public Result<LoginVO> smsLogin(@Valid @RequestBody SmsLoginDTO dto, HttpServletRequest req) {
        return Result.success(authService.smsLogin(dto, req.getRemoteAddr()));
    }

    @Operation(summary = "密码登录")
    @PostMapping("/login/password")
    public Result<LoginVO> pwdLogin(@Valid @RequestBody PasswordLoginDTO dto, HttpServletRequest req) {
        return Result.success(authService.passwordLogin(dto, req.getRemoteAddr()));
    }

    @Operation(summary = "注册(设置密码)")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        authService.register(dto);
        return Result.success();
    }

    @Operation(summary = "刷新令牌")
    @PostMapping("/refresh")
    public Result<LoginVO> refresh(@RequestBody RefreshDTO dto) {
        return Result.success(authService.refresh(dto.getRefreshToken()));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "X-Device", defaultValue = "web") String device) {
        authService.logout(device);
        return Result.success();
    }
}
```

再加一个「当前用户信息」接口，验证登录态：

路径：`member/controller/MemberController.java`

```java
@Tag(name = "会员-我的")
@RestController
@RequestMapping("/api/v1/member")
@RequiredArgsConstructor
public class MemberController {

    private final UserMapper userMapper;

    @Operation(summary = "我的资料(需登录)")
    @GetMapping("/me")
    public Result<User> me(@CurrentUser Long userId) {
        User u = userMapper.selectById(userId);
        u.setPassword(null);   // 绝不把密码哈希返回前端
        return Result.success(u);
    }
}
```

---

## 15. 权限加载与 @PreAuthorize

给第 1 卷的 FAQ 修改/删除加权限（演示方法级鉴权）。在 `FaqController` 的新增/修改/删除方法上加：

```java
@PreAuthorize("hasAuthority('content:faq:edit')")
@PostMapping
public Result<Long> create(...) { ... }

@PreAuthorize("hasAuthority('content:faq:edit')")
@PutMapping("/{id}")
public Result<Void> update(...) { ... }

@PreAuthorize("hasAuthority('content:faq:edit')")
@DeleteMapping("/{id}")
public Result<Void> delete(...) { ... }
```

> 因为 `SecurityConfig` 已允许 GET 公开，普通会员可读；只有带 `content:faq:edit` 权限点的 ADMIN 能写。普通会员无此权限点 → 返回 403 JSON。
> 快速造一个管理员：把某用户在 `uc_user_role` 里插 `role_id=2`。

造管理员密码（可在单测/启动后手动编码）：也可临时加一个内部接口用 `passwordEncoder.encode("123456")` 得到 `$2a$10$...` 后更新到库。

---

## 16. 观演人模块（带数据权限示例）

实体 `Viewer`（表 uc_viewer）、`ViewerMapper`、DTO、Service 略，重点看 Controller 如何**防止水平越权**：

路径：`member/controller/ViewerController.java`

```java
@Tag(name = "会员-观演人")
@RestController
@RequestMapping("/api/v1/member/viewers")
@RequiredArgsConstructor
public class ViewerController {

    private final ViewerService viewerService;

    @PostMapping
    public Result<Long> add(@CurrentUser Long userId, @Valid @RequestBody ViewerSaveDTO dto) {
        return Result.success(viewerService.add(userId, dto));
    }

    @GetMapping
    public Result<List<ViewerVO>> list(@CurrentUser Long userId) {
        return Result.success(viewerService.listByUser(userId));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@CurrentUser Long userId,
                               @PathVariable Long id,
                               @Valid @RequestBody ViewerSaveDTO dto) {
        // 关键：把 userId 传进去，Service 里 WHERE id=? AND user_id=?，
        // A 用户永远改不到 B 用户的数据（防越权）
        viewerService.updateForUser(userId, id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentUser Long userId, @PathVariable Long id) {
        viewerService.deleteForUser(userId, id);
        return Result.success();
    }
}
```

ServiceImpl 更新示例：

```java
public void updateForUser(Long userId, Long id, ViewerSaveDTO dto) {
    Viewer exist = viewerMapper.selectOne(Wrappers.<Viewer>lambdaQuery()
            .eq(Viewer::getId, id).eq(Viewer::getUserId, userId));
    if (exist == null) {
        throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
    Viewer v = new Viewer();
    BeanUtils.copyProperties(dto, v);
    v.setId(id);
    viewerMapper.updateById(v);
}
```

> 铁律：所有「我的 xx」都用 `@CurrentUser` 拿服务端可信的 userId，绝不接收前端传的 userId。

---

## 17. 前端 api.js 改造与登录页

### 17.1 增强 `static/js/api.js`

在第 1 卷基础上，替换核心 request，加入 token 注入与 401 自动刷新：

```javascript
const BASE = '/api/v1';
let refreshing = null;   // 防并发刷新

function getAccess() { return localStorage.getItem('mars_access'); }
function getRefresh() { return localStorage.getItem('mars_refresh'); }
function saveTokens(d) {
  localStorage.setItem('mars_access', d.accessToken);
  localStorage.setItem('mars_refresh', d.refreshToken);
  localStorage.setItem('mars_uid', d.userId);
}

async function rawRequest(method, path, options = {}) {
  const { query, body, auth = true } = options;
  let url = BASE + path;
  if (query) {
    const qs = new URLSearchParams(
      Object.entries(query).filter(([,v]) => v !== undefined && v !== null && v !== '')
    ).toString();
    if (qs) url += '?' + qs;
  }
  const headers = { 'Accept': 'application/json' };
  if (auth && getAccess()) headers['Authorization'] = 'Bearer ' + getAccess();
  if (body) headers['Content-Type'] = 'application/json';
  return fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined });
}

async function request(method, path, options) {
  let resp = await rawRequest(method, path, options);

  // access 过期 → 用 refresh 换新后重试一次
  if (resp.status === 401 && options.auth !== false && getRefresh() && !options._retry) {
    try {
      if (!refreshing) {
        refreshing = rawRequest('POST', '/auth/refresh', { body: { refreshToken: getRefresh() }, auth: false });
      }
      const r = await refreshing; refreshing = null;
      const j = await r.json();
      if (j.code === 0) { saveTokens(j.data); }
      else { throw new Error(j.message); }
    } catch (e) {
      localStorage.clear();
      if (!location.pathname.endsWith('login.html')) location.href = '/pages/login.html';
      throw e;
    }
    options._retry = true;
    resp = await rawRequest(method, path, options);
  }

  let json;
  try { json = await resp.json(); }
  catch { throw new Error('HTTP ' + resp.status); }
  if (resp.status === 403) throw new Error('没有权限执行该操作');
  if (json.code !== 0) throw new Error(json.message || '请求失败');
  return json.data;
}

window.MarsApi = {
  get:  (p, query, opts) => request('GET', p, { ...opts, query }),
  post: (p, body, opts)  => request('POST', p, { ...opts, body }),
  put:  (p, body, opts)  => request('PUT', p, { ...opts, body }),
  del:  (p, opts)        => request('DELETE', p, opts),
  saveTokens
};
```

### 17.2 新建登录页 `static/pages/login.html`

（沿用 pages.css 顶栏风格，核心表单脚本如下）

```html
<section class="page-act" style="max-width:420px;margin:0 auto;">
  <div class="info-head"><h2 class="info-head__title">Mars <em>Login</em></h2></div>
  <input id="phone" placeholder="手机号" maxlength="11" style="width:100%;margin-bottom:.8rem;padding:.8rem">
  <div style="display:flex;gap:.5rem;margin-bottom:.8rem">
    <input id="code" placeholder="验证码" style="flex:1;padding:.8rem">
    <button id="sendBtn" type="button" style="padding:.8rem 1rem">发送验证码</button>
  </div>
  <button id="loginBtn" style="width:100%;padding:.9rem">登录 / 注册</button>
  <p id="tip" style="margin-top:1rem;color:#F4A261"></p>
</section>
<script src="../js/api.js?v=2"></script>
<script>
  const $ = id => document.getElementById(id);
  $('sendBtn').onclick = async () => {
    try { await MarsApi.post('/auth/sms/send?phone=' + $('phone').value, null, { auth:false });
      $('tip').textContent = '验证码已发送（查看后端控制台）';
    } catch(e){ $('tip').textContent = e.message; }
  };
  $('loginBtn').onclick = async () => {
    try {
      const d = await MarsApi.post('/auth/login/sms',
        { phone: $('phone').value, code: $('code').value, device: 'web' }, { auth:false });
      MarsApi.saveTokens(d);
      location.href = '/pages/artist.html';
    } catch(e){ $('tip').textContent = e.message; }
  };
</script>
```

> 验证码直接看 IDEA Run 控制台的 `【火星演唱会】...验证码为：123456`，填进去即可登录，无需短信平台。

---

## 18. 测试与排错

### 18.1 用 IDEA HTTP Client（`http/auth.http`）

```http
### 发送验证码
POST http://localhost:8080/api/v1/auth/sms/send?phone=13800138000

### 验证码登录（code 填控制台打印的）
# @name login
POST http://localhost:8080/api/v1/auth/login/sms
Content-Type: application/json

{ "phone": "13800138000", "code": "123456", "device": "web" }

### 把返回的 accessToken 存为变量
> {% client.global.set("token", response.body.data.accessToken);
     client.global.set("refresh", response.body.data.refreshToken); %}

### 无 token 访问受保护接口 → 401
GET http://localhost:8080/api/v1/member/me

### 带 token 访问 → 200
GET http://localhost:8080/api/v1/member/me
Authorization: Bearer {{token}}

### 普通会员尝试改 FAQ → 403（先把该用户设为 role_id=2 再试 → 200）
PUT http://localhost:8080/api/v1/faqs/1
Authorization: Bearer {{token}}
Content-Type: application/json

{ "category":"SHOW","questionEn":"x?","answer":"y","status":1 }

### 刷新令牌
POST http://localhost:8080/api/v1/auth/refresh
Content-Type: application/json

{ "refreshToken": "{{refresh}}" }
```

### 18.2 排查表
| 现象 | 原因/解决 |
|---|---|
| 所有接口 401/403 | 检查白名单路径是否与实际一致；静态资源路径要在 permitAll |
| 带了 token 仍 401 | Redis 登录态 key 不一致（device 不同）、jti 不匹配；用 IDEA Redis 面板看 `mars:token:access:*` |
| `@PreAuthorize` 不生效 | 漏加 `@EnableMethodSecurity` |
| 密码登录总失败 | 库里密码必须是 BCrypt 密文，不能存明文 |
| `There is no PasswordEncoder` | 注册了 BCryptPasswordEncoder Bean 即可 |
| token 过期时间不对 | 检查 yml 单位是秒、JwtProperties 绑定成功 |
| 接口偶发串用户 | JwtAuthenticationFilter finally 没清理 UserContext（已处理，勿删） |
| Redis 连接拒绝 | Redis 未启动/端口不对 |

### 18.3 本卷 Checklist
- [ ] 验证码登录自动建号并返回双 token
- [ ] 无 token 访问 `/member/me` 返回 401 JSON
- [ ] 带正确 token 返回用户资料且不含 password
- [ ] 删除 Redis access key 后旧 token 立即失效（踢人）
- [ ] refresh 后旧 refresh 再用报失效（旋转成功）
- [ ] 普通会员改 FAQ 返回 403，管理员 200
- [ ] A 用户无法修改 B 用户的观演人（越权拦截）
- [ ] 前端登录页跑通，api.js 遇 401 自动刷新一次

---

## 下一卷预告（第 3 卷：演出票务与订单交易）
建演出/场馆/分区/票档/场次表与后台 CRUD；购物车；提交订单的幂等号、金额服务端重算引擎、订单状态机（轻量自研）；`@Transactional` 事务边界与失效场景；订单列表分页与超时未支付标记。
