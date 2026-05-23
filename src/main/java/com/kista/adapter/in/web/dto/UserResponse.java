package com.kista.adapter.in.web.dto;

import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.model.user.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record UserResponse(
        @Schema(description = "사용자 고유 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID id,
        @Schema(description = "닉네임", example = "홍길동")
        String nickname,
        @Schema(description = "계정 상태 (PENDING/APPROVED/REJECTED)", example = "APPROVED")
        User.UserStatus status,
        @Schema(description = "텔레그램 알림 설정 여부", example = "true")
        boolean hasTelegram,
        @Schema(description = "역할 (USER/ADMIN)", example = "USER")
        User.UserRole role,
        @Schema(description = "텔레그램 봇 username (null이면 미연결)", example = "narafu_kista_bot")
        String telegramBotUsername,
        @Schema(description = "알림 채널 (TELEGRAM/FCM/ALL)", example = "TELEGRAM")
        NotificationChannel notificationChannel
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.id(),
                user.nickname(),
                user.status(),
                user.telegramChatId() != null,
                user.role(),
                user.telegramBotUsername(),
                user.notificationChannel()
        );
    }
}
