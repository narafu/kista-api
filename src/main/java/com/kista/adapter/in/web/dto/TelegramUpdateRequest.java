package com.kista.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

// 텔레그램 설정 요청 body
public record TelegramUpdateRequest(@NotBlank String botToken, @NotBlank String chatId) {}
