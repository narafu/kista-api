package com.kista.adapter.out.persistence.user;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class UserEntity extends BaseAuditEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id; // 카카오 OAuth UID — DB가 아닌 앱에서 할당

    @Column(name = "kakao_id", nullable = false, length = 50)
    private String kakaoId; // 유니크 제약은 DB의 uq_users_kakao_id_active(활성 row 한정 partial index)가 담당

    @Column(length = 100)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private User.UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private User.UserRole role; // 사용자 권한 (USER / ADMIN)

    @Column(name = "telegram_bot_token", length = 512)
    private String telegramBotToken; // AES-256 암호화 저장

    @Column(name = "telegram_chat_id", length = 50)
    private String telegramChatId;

    @Column(name = "telegram_bot_username", length = 64)
    private String telegramBotUsername; // 평문 저장 (공개 정보)

    @Column(name = "reject_reason")
    private String rejectReason; // 반려 사유 (REJECTED 상태에서만 의미, null 가능)

    @Column(name = "last_reapplied_at")
    private Instant lastReappliedAt; // nullable — 쿨다운 기준 시각

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_channel", nullable = false, length = 20)
    private NotificationChannel notificationChannel; // 알림 수단 (기본: NONE)

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨

    static UserEntity fromModel(User user) {
        UserEntity e = new UserEntity();
        e.id = user.id();
        e.kakaoId = user.kakaoId();
        e.nickname = user.nickname();
        e.status = user.status();
        e.role = user.role();
        e.telegramBotToken = user.telegramBotToken();
        e.telegramChatId = user.telegramChatId();
        e.telegramBotUsername = user.telegramBotUsername();
        e.rejectReason = user.rejectReason();
        e.lastReappliedAt = user.lastReappliedAt();
        e.notificationChannel = user.notificationChannel();
        return e;
    }

    User toModel() {
        return new User(id, kakaoId, nickname, status, role,
                telegramBotToken, telegramChatId, telegramBotUsername, rejectReason, lastReappliedAt,
                notificationChannel);
    }
}
