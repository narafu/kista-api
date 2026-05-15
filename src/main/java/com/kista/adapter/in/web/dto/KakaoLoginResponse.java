package com.kista.adapter.in.web.dto;

public record KakaoLoginResponse(
        String accessToken,  // 발급된 JWT 액세스 토큰
        String tokenType,    // 토큰 타입 (Bearer)
        long expiresIn,      // 토큰 유효 기간 (초)
        UserResponse user    // 로그인한 사용자 정보
) {}
