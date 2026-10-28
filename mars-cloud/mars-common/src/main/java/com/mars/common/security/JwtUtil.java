package com.mars.common.security;

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
                .signWith(key)
                .compact();
    }

    /** 解析，验签失败/过期会抛 JwtException，由调用方捕获 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
