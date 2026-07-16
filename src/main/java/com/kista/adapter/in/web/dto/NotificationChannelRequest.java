package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// 알림 채널 변경 요청 body
public record NotificationChannelRequest(
        @Schema(description = "알림 채널 (NONE/TELEGRAM/FCM/ALL)", example = "TELEGRAM")
        @NotBlank String channel) {}
