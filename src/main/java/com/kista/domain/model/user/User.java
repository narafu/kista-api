package com.kista.domain.model.user;

import java.time.Instant;
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
        Instant createdAt,
        Instant updatedAt,
        Instant lastReappliedAt,        // nullable — 마지막 reapply()/reject() 호출 시점 (쿨다운 기준)
        NotificationChannel notificationChannel // 알림 수단 (기본: TELEGRAM)
) {
    public enum UserRole { USER, ADMIN }

    public enum UserStatus {
        PENDING,  // 관리자 승인 대기 중
        ACTIVE,   // 승인 완료, 서비스 이용 가능
        REJECTED  // 거절됨 (재신청 가능)
    }
}
