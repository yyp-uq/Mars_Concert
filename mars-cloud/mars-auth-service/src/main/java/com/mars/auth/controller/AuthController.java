package com.mars.auth.controller;

import com.mars.common.result.Result;
import com.mars.common.security.UserContext;
import com.mars.auth.model.dto.*;
import com.mars.auth.model.vo.LoginVO;
import com.mars.auth.service.AuthService;
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

    @Operation(summary = "微信小程序登录/自动注册")
    @PostMapping("/login/wechat")
    public Result<LoginVO> wechatLogin(@Valid @RequestBody WechatLoginDTO dto, HttpServletRequest req) {
        return Result.success(authService.wechatLogin(dto, req.getRemoteAddr()));
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
