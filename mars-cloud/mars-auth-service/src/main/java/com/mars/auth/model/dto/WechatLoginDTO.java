package com.mars.auth.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WechatLoginDTO {
    @NotBlank(message = "微信登录 code 不能为空")
    private String code;      // wx.login() 获取的临时凭证，5分钟有效且仅能用一次

    private String device;    // 登录端标识：mini / web
}
