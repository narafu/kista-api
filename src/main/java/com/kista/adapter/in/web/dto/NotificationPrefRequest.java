package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 알림 타입별 on/off 요청 body
public record NotificationPrefRequest(
        @Schema(description = "해당 알림 타입 수신 여부", example = "true")
        boolean enabled) {}
