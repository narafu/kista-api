package com.kista.notify.domain.model;

import com.kista.sharedkernel.NotificationChannel;

import java.util.UUID;

// 사용자 알림 수신자 — notify가 알림 발송에 실제로 읽는 필드만 담은 notify 소유 투영(User 애그리게이트 비의존).
// trading-core의 TradingUserProfile과 같은 접근 — 변환은 gateway 어댑터의 NotificationRecipients.from(User)가 맡는다
public record NotificationRecipient(
        UUID userId,                          // 수신자 사용자 ID (FCM 토큰 조회·승인/거절 버튼 payload)
        String nickname,                      // 관리자 알림에 표시할 닉네임
        NotificationChannel notificationChannel, // 알림 수단 (채널 기반 라우팅 대상)
        String telegramBotToken,              // 사용자 텔레그램 봇 토큰 (복호화 값, null 가능)
        String telegramChatId,                // 사용자 텔레그램 Chat ID (null 가능)
        String rejectReason                   // 가입 거절 사유 (거절 알림에만 사용, null 가능)
) {
    // 텔레그램 봇 토큰 + Chat ID가 모두 설정된 경우에만 true
    public boolean hasTelegramBot() {
        return telegramBotToken != null && !telegramBotToken.isBlank() && telegramChatId != null;
    }
}
