package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// 텔레그램 설정 요청 body
public record TelegramUpdateRequest(
        @Schema(description = "사용자 텔레그램 봇 토큰")
        @NotBlank String botToken,
        @Schema(description = "텔레그램 채팅 ID")
        @NotBlank String chatId) {}
