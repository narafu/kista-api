package com.kista.trading.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

// user 소유 알림·잔고검증·활성여부의 trading-core 복제본. 감사 대상이 아니라 복제본이라
// BaseAuditEntity/BaseCreatedAtEntity를 상속하지 않고 동기화 시각(updatedAt)만 직접 관리한다.
// @Id가 할당식(@GeneratedValue 없음)이라 save()가 곧 upsert(merge)다.
@Entity
@Table(name = "user_notify_profile", schema = "trading")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
class UserNotifyProfileEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId; // 사용자 식별자 (원본 public.users.id)

    @Column(name = "notification_prefs", nullable = false)
    private String notificationPrefsJson; // NotificationType->Boolean 직렬화 JSON

    @Column(name = "balance_check_enabled", nullable = false)
    private boolean balanceCheckEnabled; // 잔고검증 활성 여부

    @Column(name = "is_active", nullable = false)
    private boolean active; // UserStatus.ACTIVE 여부 — findAllActive() 판정 기준

    @Column(name = "telegram_bot_token", length = 512)
    private String telegramBotToken; // AES-256 암호화 저장(trading-core persistence 경계에서 암복호화), null 가능

    @Column(name = "chat_id", length = 64)
    private String chatId; // 텔레그램 chat ID(평문), null 가능

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt; // 마지막 동기화 시각
}
