package com.kista.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

// 알림 채널 변경 요청 body
public record NotificationChannelRequest(@NotBlank String channel) {}
