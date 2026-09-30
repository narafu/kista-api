package com.kista.user.domain.model;

import com.kista.sharedkernel.NotificationChannel;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

public record User(
        UUID id,                        // 카카오 OAuth UID (앱에서 할당)
        String kakaoId,                 // 카카오 고유 ID
        @With String nickname,          // 카카오 닉네임
        @With String email,             // 카카오 계정 이메일 (AES-256 암호화 저장, 이메일 동의 안 하면 null 가능)
        UserStatus status,              // 계정 상태
        @With UserRole role,            // 사용자 권한 (USER / ADMIN)
        String telegramBotToken,        // 전체 계좌 텔레그램 봇 토큰 (AES-256 암호화 저장, null 가능)
        String telegramChatId,          // 전체 계좌 텔레그램 Chat ID (null 가능)
        String telegramBotUsername,     // 텔레그램 봇 username (저장 시 getMe로 취득, 평문, null 가능)
        String rejectReason,            // 반려 사유 (REJECTED 상태에서만 의미, null 가능)
        Instant lastReappliedAt,        // nullable — 마지막 reapply()/reject() 호출 시점 (쿨다운 기준)
        @With NotificationChannel notificationChannel // 알림 수단
) {
    public static final NotificationChannel DEFAULT_CHANNEL = NotificationChannel.NONE; // 신규 유저 기본값

    // 재신청 쿨다운 미경과 시 발생 — GlobalExceptionHandler에서 429(Retry-After) 매핑
    public static class CooldownException extends RuntimeException {
        private final Instant retryAfter; // 재신청 가능 시각

        public CooldownException(Instant retryAfter) {
            super("재신청 대기 중입니다. 가능 시각: " + retryAfter);
            this.retryAfter = retryAfter;
        }

        public Instant getRetryAfter() { return retryAfter; }
    }

    // 텔레그램 봇 토큰 + Chat ID가 모두 설정된 경우에만 true
    public boolean hasTelegramBot() {
        return telegramBotToken != null && !telegramBotToken.isBlank() && telegramChatId != null;
    }

    // 상태만 교체 — 나머지 필드 보존
    public User withStatus(UserStatus newStatus) {
        return new User(id, kakaoId, nickname, email, newStatus, role,
                telegramBotToken, telegramChatId, telegramBotUsername, rejectReason,
                lastReappliedAt, notificationChannel);
    }

    // 상태 + lastReappliedAt 동시 교체 (reject/reapply 용)
    public User withStatus(UserStatus newStatus, Instant newLastReappliedAt) {
        return new User(id, kakaoId, nickname, email, newStatus, role,
                telegramBotToken, telegramChatId, telegramBotUsername, rejectReason,
                newLastReappliedAt, notificationChannel);
    }

    // 거절 전용 — REJECTED 전환 + 사유 세팅(덮어쓰기) + lastReappliedAt 갱신(24h 카운트다운 시작)
    public User withRejection(String reason) {
        return new User(id, kakaoId, nickname, email, UserStatus.REJECTED, role,
                telegramBotToken, telegramChatId, telegramBotUsername, reason,
                Instant.now(), notificationChannel);
    }

    // 텔레그램 설정 교체
    public User withTelegram(String botToken, String chatId, String botUsername) {
        return new User(id, kakaoId, nickname, email, status, role,
                botToken, chatId, botUsername, rejectReason, lastReappliedAt, notificationChannel);
    }

}
