package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record KakaoLoginResponse(
        @Schema(description = "발급된 JWT 액세스 토큰", example = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEifQ.sig")
        String accessToken,
        @Schema(description = "토큰 타입", example = "bearer")
        String tokenType,
        @Schema(description = "토큰 유효 기간 (초)", example = "604800")
        long expiresIn,
        @Schema(description = "로그인한 사용자 정보")
        UserResponse user
) {}
