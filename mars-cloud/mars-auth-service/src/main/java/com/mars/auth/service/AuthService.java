package com.mars.auth.service;

import com.mars.auth.model.dto.*;
import com.mars.auth.model.vo.LoginVO;

public interface AuthService {
    void sendSmsCode(String phone);
    LoginVO smsLogin(SmsLoginDTO dto, String ip);
    LoginVO wechatLogin(WechatLoginDTO dto, String ip);
    LoginVO passwordLogin(PasswordLoginDTO dto, String ip);
    void register(RegisterDTO dto);
    LoginVO refresh(String refreshToken);
    void logout(String device);
}
