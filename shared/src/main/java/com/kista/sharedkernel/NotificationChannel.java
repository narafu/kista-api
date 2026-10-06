package com.kista.sharedkernel;

import java.util.Optional;

// 알림 수단 — user(설정 저장)와 notify(채널 라우팅)가 공유하는 어휘라 sharedkernel 소속(2026-09-30, notify 순수 게이트웨이화).
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

    // 텔레그램 봇을 연결하면 텔레그램을 수신 경로에 더한다 — NONE→TELEGRAM, FCM→ALL
    public NotificationChannel withTelegram() {
        return switch (this) {
            case NONE -> TELEGRAM;
            case FCM -> ALL;
            default -> this;
        };
    }

    // 텔레그램 봇을 해제하면 텔레그램을 수신 경로에서 뺀다 — TELEGRAM→NONE, ALL→FCM
    public NotificationChannel withoutTelegram() {
        return switch (this) {
            case TELEGRAM -> NONE;
            case ALL -> FCM;
            default -> this;
        };
    }
}
