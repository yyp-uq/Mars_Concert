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
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
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
