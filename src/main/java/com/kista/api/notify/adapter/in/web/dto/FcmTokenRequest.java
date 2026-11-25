package com.kista.api.notify.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// FCM 토큰 등록 요청 body
public record FcmTokenRequest(
        @Schema(description = "FCM 디바이스 토큰")
        @NotBlank(message = "FCM 토큰은 필수입니다.") String token,
        @Schema(description = "디바이스 플랫폼", example = "android")
        @NotBlank(message = "디바이스 플랫폼은 필수입니다.") String platform) {}
