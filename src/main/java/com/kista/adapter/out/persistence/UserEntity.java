package com.kista.adapter.out.persistence;

import com.kista.domain.model.User;
import com.kista.domain.model.UserStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id; // Supabase Auth UID — DB가 아닌 앱에서 할당

    @Column(name = "kakao_id", nullable = false, unique = true, length = 50)
    private String kakaoId;

    @Column(length = 100)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private UserStatus status;

    @Column(name = "telegram_bot_token", length = 255)
    private String telegramBotToken; // AES-256 암호화 저장

    @Column(name = "telegram_chat_id", length = 50)
    private String telegramChatId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserEntity() {}

    static UserEntity fromModel(User user) {
        UserEntity e = new UserEntity();
        e.id = user.id();
        e.kakaoId = user.kakaoId();
        e.nickname = user.nickname();
        e.status = user.status();
        e.telegramBotToken = user.telegramBotToken();
        e.telegramChatId = user.telegramChatId();
        e.createdAt = user.createdAt() != null ? user.createdAt() : Instant.now();
        e.updatedAt = user.updatedAt() != null ? user.updatedAt() : Instant.now();
        return e;
    }

    User toModel() {
        return new User(id, kakaoId, nickname, status,
                telegramBotToken, telegramChatId, createdAt, updatedAt);
    }
}
