package com.kista.trading.domain.model;

import com.kista.sharedkernel.NotificationChannel;
import com.kista.sharedkernel.NotificationType;

import java.util.Map;
import java.util.UUID;

// trading이 필요로 하는 사용자 알림·잔고검증 설정만 담은 투영 — user.domain.model.User/UserSettings 전체를 참조하지 않기 위한 포트 역전
public record TradingUserProfile(
        UUID userId,
        Map<NotificationType, Boolean> notificationPrefs, // UserSettings.notificationPrefs와 동일 shape(byte-identical)
        boolean balanceCheckEnabled,
        String telegramBotToken, // 평문(persistence adapter 경계에서 이미 복호화됨), null 가능
        String chatId,           // 텔레그램 chat ID, null 가능
        NotificationChannel notificationChannel // 알림 수단, null이면 채널 판정 없이 봇 연결만 본다(채널 복제 전 행)
) {
    public TradingUserProfile(UUID userId, Map<NotificationType, Boolean> notificationPrefs, boolean balanceCheckEnabled,
                              String telegramBotToken, String chatId) {
        this(userId, notificationPrefs, balanceCheckEnabled, telegramBotToken, chatId, null);
    }

    // 텔레그램으로 보낼 수 있는지 — 봇·chatId가 있고 알림 채널에 텔레그램이 포함될 때
    public boolean telegramEnabled() {
        return telegramBotToken != null && chatId != null
                && (notificationChannel == null || notificationChannel.includesTelegram());
    }

    // 미설정 시 기본 활성 — UserSettings.isNotificationEnabled()와 동일 규칙
    public boolean isNotificationEnabled(NotificationType type) {
        return notificationPrefs.getOrDefault(type, true);
    }
}
