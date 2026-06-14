package com.kista.adapter.out.persistence.user;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.UserPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class UserPersistenceAdapter implements UserPort {

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

    @Override
    public List<User> findAll() {
        return jpaRepository.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public List<User> findAllByStatus(User.UserStatus status) {
        return jpaRepository.findAllByStatus(status).stream().map(this::toDomain).toList();
    }

    @Override
    public long countAll() {
        return jpaRepository.count();
    }

    @Override
    public long countByStatus(User.UserStatus status) {
        return jpaRepository.countByStatus(status);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now());
    }

    // persistence 경계에서 telegramBotToken 암호화
    private User encrypt(User user) {
        if (user.telegramBotToken() == null) return user;
        return new User(user.id(), user.kakaoId(), user.nickname(), user.status(), user.role(),
                crypto.encrypt(user.telegramBotToken()), user.telegramChatId(), user.telegramBotUsername(),
                user.lastReappliedAt(),
                user.notificationChannel() != null ? user.notificationChannel() : NotificationChannel.TELEGRAM,
                user.balanceCheckEnabled());
    }

    // persistence 경계에서 telegramBotToken 복호화
    private User toDomain(UserEntity e) {
        User raw = e.toModel();
        if (raw.telegramBotToken() == null) return raw;
        return new User(raw.id(), raw.kakaoId(), raw.nickname(), raw.status(), raw.role(),
                crypto.decrypt(raw.telegramBotToken()), raw.telegramChatId(), raw.telegramBotUsername(),
                raw.lastReappliedAt(),
                raw.notificationChannel() != null ? raw.notificationChannel() : NotificationChannel.TELEGRAM,
                raw.balanceCheckEnabled());
    }
}
