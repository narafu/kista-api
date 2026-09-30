package com.kista.user.domain.model;

import java.util.Optional;

// 알림 수단 — user 단독 소비라 sharedkernel "공용 어휘"에서 user.domain.model로 되돌림(옛 User nested enum).
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
