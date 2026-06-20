package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record RefreshResponse(
        @Schema(description = "새로 발급된 JWT 액세스 토큰")
        String accessToken,
        @Schema(description = "토큰 타입", example = "bearer")
        String tokenType,
        @Schema(description = "토큰 유효 기간 (초)", example = "604800")
        long expiresIn
) {}
