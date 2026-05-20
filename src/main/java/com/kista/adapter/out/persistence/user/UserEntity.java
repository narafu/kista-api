package com.kista.adapter.out.persistence.user;

import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserRole;
import com.kista.domain.model.user.UserStatus;
import com.kista.adapter.out.persistence.BaseAuditEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserEntity extends BaseAuditEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id; // 카카오 OAuth UID — DB가 아닌 앱에서 할당

    @Column(name = "kakao_id", nullable = false, unique = true, length = 50)
    private String kakaoId;

    @Column(length = 100)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role; // 사용자 권한 (USER / ADMIN)

    @Column(name = "telegram_bot_token", length = 512)
    private String telegramBotToken; // AES-256 암호화 저장

    @Column(name = "telegram_chat_id", length = 50)
    private String telegramChatId;

    @Column(name = "telegram_bot_username", length = 64)
    private String telegramBotUsername; // 평문 저장 (공개 정보)

    @Column(name = "last_reapplied_at")
    private Instant lastReappliedAt; // nullable — 쿨다운 기준 시각

    protected UserEntity() {}

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
        e.createdAt = user.createdAt(); // null이면 @CreatedDate가 INSERT 시 자동 설정
        e.lastReappliedAt = user.lastReappliedAt();
        return e;
    }

    User toModel() {
        return new User(id, kakaoId, nickname, status, role,
                telegramBotToken, telegramChatId, telegramBotUsername, createdAt, updatedAt, lastReappliedAt);
    }
}
