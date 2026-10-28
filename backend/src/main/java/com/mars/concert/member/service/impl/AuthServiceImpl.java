package com.mars.concert.member.service.impl;

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
            // 分配默认会员角色（学习期固定 role_id=1）
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
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
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
