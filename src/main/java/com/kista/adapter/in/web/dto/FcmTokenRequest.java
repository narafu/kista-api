package com.kista.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

// FCM 토큰 등록 요청 body
public record FcmTokenRequest(@NotBlank String token, @NotBlank String platform) {}
