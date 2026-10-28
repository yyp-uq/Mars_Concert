package com.mars.auth.controller;

import com.mars.common.result.Result;
import com.mars.common.web.CurrentUser;
import com.mars.auth.entity.User;
import com.mars.auth.mapper.UserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        if (u != null) {
            u.setPassword(null);   // 绝不把密码哈希返回前端
        }
        return Result.success(u);
    }
}
