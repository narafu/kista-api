package com.kista.sharedkernel;

import java.util.Optional;

// 알림 수단 — User 도메인 nested enum에서 sharedkernel로 이관.
// 상수명 byte-identical 유지 필수 — UserEntity.notificationChannel @Enumerated(STRING) DB 컬럼과 직결.
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
