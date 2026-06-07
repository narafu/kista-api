package com.kista.domain.model.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record User(
        UUID id,                        // 카카오 OAuth UID (앱에서 할당)
        String kakaoId,                 // 카카오 고유 ID
        String nickname,                // 카카오 닉네임
        UserStatus status,              // 계정 상태
        UserRole role,                  // 사용자 권한 (USER / ADMIN)
        String telegramBotToken,        // 전체 계좌 텔레그램 봇 토큰 (AES-256 암호화 저장, null 가능)
        String telegramChatId,          // 전체 계좌 텔레그램 Chat ID (null 가능)
        String telegramBotUsername,     // 텔레그램 봇 username (저장 시 getMe로 취득, 평문, null 가능)
        Instant lastReappliedAt,        // nullable — 마지막 reapply()/reject() 호출 시점 (쿨다운 기준)
        NotificationChannel notificationChannel // 알림 수단 (기본: TELEGRAM)
) {
    public enum UserRole { USER, ADMIN }

    public enum UserStatus {
        PENDING,  // 관리자 승인 대기 중
        ACTIVE,   // 승인 완료, 서비스 이용 가능
        REJECTED  // 거절됨 (재신청 가능)
    }

    public enum NotificationChannel {
        NONE,       // 알림 없음
        TELEGRAM,   // 텔레그램 봇 알림
        FCM,        // Firebase Cloud Messaging 푸시
        ALL;        // 텔레그램 + FCM 동시 발송

        // 안전한 파싱 — 대소문자 무시, 불일치 시 empty 반환
        public static Optional<NotificationChannel> tryParse(String value) {
            if (value == null) return Optional.empty();
            try {
                return Optional.of(valueOf(value.toUpperCase()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        public boolean includesTelegram() { return this == TELEGRAM || this == ALL; }
        public boolean includesFcm()      { return this == FCM      || this == ALL; }
    }

    // 상태만 교체 — 나머지 필드 보존
    public User withStatus(UserStatus newStatus) {
        return new User(id, kakaoId, nickname, newStatus, role,
                telegramBotToken, telegramChatId, telegramBotUsername,
                lastReappliedAt, notificationChannel);
    }

    // 상태 + lastReappliedAt 동시 교체 (reject/reapply 용)
    public User withStatus(UserStatus newStatus, Instant newLastReappliedAt) {
        return new User(id, kakaoId, nickname, newStatus, role,
                telegramBotToken, telegramChatId, telegramBotUsername,
                newLastReappliedAt, notificationChannel);
    }

    // 텔레그램 설정 교체
    public User withTelegram(String botToken, String chatId, String botUsername) {
        return new User(id, kakaoId, nickname, status, role,
                botToken, chatId, botUsername, lastReappliedAt, notificationChannel);
    }

    // 알림 채널 교체
    public User withNotificationChannel(NotificationChannel channel) {
        return new User(id, kakaoId, nickname, status, role,
                telegramBotToken, telegramChatId, telegramBotUsername,
                lastReappliedAt, channel);
    }
}
