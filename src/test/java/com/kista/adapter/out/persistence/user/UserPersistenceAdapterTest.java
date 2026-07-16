package com.kista.adapter.out.persistence.user;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserPersistenceAdapter — telegramBotToken AES 암호화 왕복 매핑 테스트")
class UserPersistenceAdapterTest {

    @Mock
    private UserJpaRepository jpaRepository;

    private AesCryptoService crypto; // 실제 인스턴스 — 암복호화 왕복 검증에 mock 부적합
    private UserPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        // 테스트용 32바이트(256bit) 키 (Base64 인코딩)
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);
        crypto = new AesCryptoService(testKey);
        adapter = new UserPersistenceAdapter(jpaRepository, crypto);
    }

    private User newUser(String telegramBotToken) {
        return new User(UUID.randomUUID(), "kakao-1", "닉네임", User.UserStatus.ACTIVE, User.UserRole.USER,
                telegramBotToken, "chat-1", "bot-username", null, null, User.NotificationChannel.TELEGRAM);
    }

    @Test
    @DisplayName("save 시 telegramBotToken이 평문과 다르게 저장되고(암호화) 복호화하면 평문이 복원된다")
    void save_encrypts_telegram_bot_token() {
        String plainToken = "plain-bot-token";
        User user = newUser(plainToken);
        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        when(jpaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        adapter.save(user);

        verify(jpaRepository).save(captor.capture());
        UserEntity saved = captor.getValue();
        assertThat(saved.getTelegramBotToken()).isNotEqualTo(plainToken); // 암호화되어 저장됨
        assertThat(crypto.decrypt(saved.getTelegramBotToken())).isEqualTo(plainToken); // 복호화하면 원본 복원
    }

    @Test
    @DisplayName("findById 시 엔티티의 암호문이 도메인 User에서 평문으로 복호화된다")
    void findById_decrypts_telegram_bot_token() {
        String plainToken = "plain-bot-token";
        String encryptedToken = crypto.encrypt(plainToken);
        UUID userId = UUID.randomUUID();
        UserEntity entity = UserEntity.fromModel(new User(userId, "kakao-1", "닉네임", User.UserStatus.ACTIVE,
                User.UserRole.USER, encryptedToken, "chat-1", "bot-username", null, null,
                User.NotificationChannel.TELEGRAM));
        when(jpaRepository.findById(userId)).thenReturn(Optional.of(entity));

        Optional<User> result = adapter.findById(userId);

        assertThat(result).isPresent();
        assertThat(result.get().telegramBotToken()).isEqualTo(plainToken); // 복호화되어 평문 반환
    }

    @Test
    @DisplayName("telegramBotToken이 null인 사용자는 null 그대로 왕복된다")
    void null_telegram_bot_token_round_trips_as_null() {
        User user = newUser(null);
        when(jpaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = adapter.save(user);

        assertThat(saved.telegramBotToken()).isNull();
    }
}
