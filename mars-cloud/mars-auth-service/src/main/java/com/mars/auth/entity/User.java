package com.mars.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("uc_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String openid;           // 微信小程序 openid（微信登录用户）
    private String unionid;          // 微信开放平台 unionid（同一主体多应用打通）
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
