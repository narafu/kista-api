package com.kista.adapter.out.persistence.user;

import com.kista.adapter.out.crypto.AesCryptoService;
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
    public Optional<User> findByTelegramChatId(String chatId) {
        return jpaRepository.findByTelegramChatId(chatId).map(this::toDomain);
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
    public long countByRole(User.UserRole role) {
        return jpaRepository.countByRole(role);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now());
    }

    // persistence 경계에서 telegramBotToken·email 암호화
    private User encrypt(User user) {
        User encrypted = user;
        if (encrypted.telegramBotToken() != null) {
            encrypted = encrypted.withTelegram(crypto.encrypt(encrypted.telegramBotToken()),
                    encrypted.telegramChatId(), encrypted.telegramBotUsername());
        }
        if (encrypted.email() != null) {
            encrypted = encrypted.withEmail(crypto.encrypt(encrypted.email()));
        }
        return encrypted;
    }

    // persistence 경계에서 telegramBotToken·email 복호화
    private User toDomain(UserEntity e) {
        User raw = e.toModel();
        if (raw.telegramBotToken() != null) {
            raw = raw.withTelegram(crypto.decrypt(raw.telegramBotToken()), raw.telegramChatId(), raw.telegramBotUsername());
        }
        if (raw.email() != null) {
            raw = raw.withEmail(crypto.decrypt(raw.email()));
        }
        return raw;
    }
}
