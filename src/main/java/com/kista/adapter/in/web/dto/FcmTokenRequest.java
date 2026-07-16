package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// FCM 토큰 등록 요청 body
public record FcmTokenRequest(
        @Schema(description = "FCM 디바이스 토큰")
        @NotBlank String token,
        @Schema(description = "디바이스 플랫폼", example = "android")
        @NotBlank String platform) {}
