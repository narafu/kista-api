package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// Swagger dev-token 엔드포인트 응답 DTO
public record TokenResponse(
        @Schema(description = "JWT 액세스 토큰", example = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEifQ.sig")
        String accessToken,
        @Schema(description = "토큰 타입", example = "bearer")
        String tokenType,
        @Schema(description = "토큰 유효 기간 (초)", example = "604800")
        long expiresIn
) {}
