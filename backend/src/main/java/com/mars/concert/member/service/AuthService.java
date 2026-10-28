package com.mars.concert.member.service;

import com.mars.concert.member.model.dto.*;
import com.mars.concert.member.model.vo.LoginVO;

public interface AuthService {
    void sendSmsCode(String phone);
    LoginVO smsLogin(SmsLoginDTO dto, String ip);
    LoginVO passwordLogin(PasswordLoginDTO dto, String ip);
    void register(RegisterDTO dto);
    LoginVO refresh(String refreshToken);
    void logout(String device);
}
