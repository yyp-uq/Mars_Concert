package com.mars.concert.member.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginVO {
    private String accessToken;
    private String refreshToken;
    private Long userId;
    private String nickname;
    private String avatar;
}
