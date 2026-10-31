package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.platform.crypto.AesStringConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import com.kista.shared.kernel.NotificationChannel;
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

// user 소유 알림·활성여부의 trading-core 복제본. 감사 대상이 아니라 복제본이라
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

    @Column(name = "is_active", nullable = false)
    private boolean active; // UserStatus.ACTIVE 여부 — findAllActive() 판정 기준

    @Convert(converter = AesStringConverter.class)
    @Column(name = "telegram_bot_token", length = 512)
    private String telegramBotToken; // 평문 보유 — DB에는 AES-256 암호문 저장(컨버터), null 가능

    @Column(name = "chat_id", length = 64)
    private String chatId; // 텔레그램 chat ID(평문), null 가능

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt; // 마지막 동기화 시각

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_channel", length = 20)
    private NotificationChannel notificationChannel; // 알림 수단, null = 채널 복제 전 행(봇 연결만 판정) — V5 ADD COLUMN이라 맨 뒤

    // 변경 이벤트 스냅샷 반영 — 채널 없는 이벤트(채널 복제 이전 root가 발행)는 저장된 채널을 지우지 않는다(NULL이 되면 봇 연결만 보고 텔레그램을 다시 보내게 된다)
    void applyChange(String prefsJson, boolean active, String telegramBotToken, String chatId,
                     Instant version, NotificationChannel channel) {
        this.notificationPrefsJson = prefsJson;
        this.active = active;
        this.telegramBotToken = telegramBotToken;
        this.chatId = chatId;
        this.updatedAt = version;
        if (channel != null) {
            this.notificationChannel = channel;
        }
    }
}
