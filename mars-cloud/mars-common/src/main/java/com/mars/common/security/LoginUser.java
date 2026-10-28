package com.mars.common.security;

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
