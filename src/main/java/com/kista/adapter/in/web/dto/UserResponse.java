package com.kista.adapter.in.web.dto;

import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.model.user.UserSettings;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record UserResponse(
        @Schema(description = "사용자 고유 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID id,
        @Schema(description = "닉네임", example = "홍길동")
        String nickname,
        @Schema(description = "계정 상태 (PENDING/ACTIVE/REJECTED)", example = "ACTIVE")
        User.UserStatus status,
        @Schema(description = "텔레그램 알림 설정 여부", example = "true")
        boolean hasTelegram,
        @Schema(description = "역할 (USER/ADMIN)", example = "USER")
        User.UserRole role,
        @Schema(description = "텔레그램 봇 username (null이면 미연결)", example = "narafu_kista_bot")
        String telegramBotUsername,
        @Schema(description = "알림 채널 (TELEGRAM/FCM/ALL/NONE)", example = "TELEGRAM")
        NotificationChannel notificationChannel,
        @Schema(description = "전략 등록·재등록 시 실잔고 검증 여부 (false=바이패스)", example = "true")
        boolean balanceCheckEnabled,
        @Schema(description = "알림 타입별 on/off (예: {\"TRADING_ALERT\": true})")
        Map<String, Boolean> notificationPrefs,
        @Schema(description = "반려 사유 (REJECTED 상태에서만 의미, null 가능)")
        String rejectReason
) {
    public static UserResponse from(User user, UserSettings settings) {
        // notificationPrefs — enum key를 String으로 변환하여 JSON 직렬화
        Map<String, Boolean> prefs = settings.notificationPrefs().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
        // REJECTED가 아니면 잔존 사유값을 노출하지 않도록 마스킹
        String maskedRejectReason = user.status() == User.UserStatus.REJECTED ? user.rejectReason() : null;
        return new UserResponse(
                user.id(),
                user.nickname(),
                user.status(),
                user.telegramChatId() != null,
                user.role(),
                user.telegramBotUsername(),
                user.notificationChannel(),
                settings.balanceCheckEnabled(),
                prefs,
                maskedRejectReason
        );
    }
}
