package com.mars.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.common.constant.RedisKeys;
import com.mars.common.enums.CommonErrorCode;
import com.mars.common.exception.BusinessException;
import com.mars.common.security.*;
import com.mars.auth.security.SecurityUserLoader;
import com.mars.common.utils.RedisService;
import com.mars.auth.entity.User;
import com.mars.auth.mapper.UserMapper;
import com.mars.auth.model.dto.*;
import com.mars.auth.model.vo.LoginVO;
import com.mars.auth.service.AuthService;
import com.mars.auth.sms.AliyunSmsSender;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.Map;
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
    private final ObjectMapper objectMapper;
    private final AliyunSmsSender smsSender;

    /** 微信小程序凭据：从环境变量注入，禁止写死在代码里 */
    @Value("${mars.wechat.mini.app-id:}")
    private String wechatAppId;
    @Value("${mars.wechat.mini.app-secret:}")
    private String wechatAppSecret;

    /** 微信接口客户端：连接/读取超时 5 秒，避免登录接口被上游拖死 */
    private final RestClient wechatClient = RestClient.builder()
            .baseUrl("https://api.weixin.qq.com")
            .requestFactory(wechatRequestFactory())
            .build();

    private static SimpleClientHttpRequestFactory wechatRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        return factory;
    }

    @Override
    public void sendSmsCode(String phone) {
        // 频控：60秒1次
        if (Boolean.TRUE.equals(redis.exists(RedisKeys.SMS_CODE + phone))) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(), "验证码发送过于频繁");
        }
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        // 开关决定真实发送还是日志模拟；发送失败会抛业务异常，不写入 Redis
        smsSender.sendLoginCode(phone, code);
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
    @Transactional
    public LoginVO wechatLogin(WechatLoginDTO dto, String ip) {
        if (wechatAppId == null || wechatAppId.isBlank()
                || wechatAppSecret == null || wechatAppSecret.isBlank()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(),
                    "未配置微信小程序 AppID/AppSecret");
        }

        // ① code 换 openid：code 由小程序 wx.login() 生成，5 分钟有效且只能使用一次
        Map<String, Object> resp;
        try {
            // 微信该接口返回 Content-Type: text/plain，先取字符串再交给 Jackson 解析
            String raw = wechatClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/sns/jscode2session")
                            .queryParam("appid", wechatAppId)
                            .queryParam("secret", wechatAppSecret)
                            .queryParam("js_code", dto.getCode())
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(String.class);
            resp = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("微信 code2Session 调用失败", e);
            throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(), "微信登录服务暂不可用，请稍后重试");
        }

        Object errcode = resp == null ? null : resp.get("errcode");
        if (errcode != null && !"0".equals(String.valueOf(errcode))) {
            log.warn("微信登录失败 errcode={}, errmsg={}", errcode, resp.get("errmsg"));
            throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(),
                    "微信登录失败：" + resp.get("errmsg"));
        }
        String openid = resp == null ? null : (String) resp.get("openid");
        String unionid = resp == null ? null : (String) resp.get("unionid");
        if (openid == null || openid.isBlank()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(), "微信登录失败：未获取到 openid");
        }

        // ② openid 已绑定则直接登录，未绑定则自动注册（微信用户可无手机号）
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getOpenid, openid));
        if (user == null) {
            user = new User();
            user.setOpenid(openid);
            user.setUnionid(unionid);
            user.setNickname("微信用户" + openid.substring(Math.max(0, openid.length() - 6)));
            user.setStatus(1);
            userMapper.insert(user);
            userMapper.insertUserRole(user.getId(), 1L);
            log.info("微信新用户自动注册 userId={}, openid={}", user.getId(), openid);
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
