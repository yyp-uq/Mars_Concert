package com.mars.auth.security;

import com.mars.auth.entity.User;
import com.mars.auth.mapper.UserMapper;
import com.mars.common.security.LoginUser;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SecurityUserLoader {

    private final UserMapper userMapper;

    /** 组装含权限的 LoginUser（登录/鉴权场景使用） */
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
