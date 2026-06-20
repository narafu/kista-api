package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// /api/auth/refresh 전용 응답 — AT + rawRefreshToken 포함
// rawRefreshToken: proxy.ts Edge Runtime이 Set-Cookie 헤더 필터링을 우회해 RT 쿠키를 직접 구성하는 데 사용
public record RefreshResponse(
        @Schema(description = "새로 발급된 JWT 액세스 토큰")
        String accessToken,
        @Schema(description = "토큰 타입", example = "bearer")
        String tokenType,
        @Schema(description = "토큰 유효 기간 (초)", example = "604800")
        long expiresIn,
        @Schema(description = "새로 발급된 raw refresh token (서버 간 전용 — 브라우저에 미노출)")
        String rawRefreshToken
) {}
