package com.kista.adapter.out.persistence;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.User;
import com.kista.domain.port.out.UserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class UserPersistenceAdapter implements UserRepository {

    private final UserJpaRepository jpaRepository;
    private final AesCryptoService crypto; // telegramBotToken AES-256 암호화/복호화

    @Override
    public Optional<User> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<User> findByKakaoId(String kakaoId) {
        return jpaRepository.findByKakaoId(kakaoId).map(this::toDomain);
    }

    @Override
    public User save(User user) {
        return toDomain(jpaRepository.save(UserEntity.fromModel(encrypt(user))));
    }

    // persistence 경계에서 telegramBotToken 암호화
    private User encrypt(User user) {
        if (user.telegramBotToken() == null) return user;
        return new User(user.id(), user.kakaoId(), user.nickname(), user.status(),
                crypto.encrypt(user.telegramBotToken()), user.telegramChatId(),
                user.createdAt(), user.updatedAt(), user.lastReappliedAt());
    }

    // persistence 경계에서 telegramBotToken 복호화
    private User toDomain(UserEntity e) {
        User raw = e.toModel();
        if (raw.telegramBotToken() == null) return raw;
        return new User(raw.id(), raw.kakaoId(), raw.nickname(), raw.status(),
                crypto.decrypt(raw.telegramBotToken()), raw.telegramChatId(),
                raw.createdAt(), raw.updatedAt(), raw.lastReappliedAt());
    }
}
