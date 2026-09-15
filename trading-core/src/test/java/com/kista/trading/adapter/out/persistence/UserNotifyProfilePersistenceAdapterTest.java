package com.kista.trading.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import com.kista.platform.crypto.AesCryptoService;
import com.kista.sharedkernel.NotificationType;
import com.kista.trading.domain.model.TradingUserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserNotifyProfilePersistenceAdapter 단위 테스트")
class UserNotifyProfilePersistenceAdapterTest {

    @Mock UserNotifyProfileJpaRepository repository;
    @Mock AesCryptoService crypto;

    private final UUID USER_ID = UUID.randomUUID();

    private UserNotifyProfilePersistenceAdapter adapter() {
        return new UserNotifyProfilePersistenceAdapter(repository, new ObjectMapper(), crypto);
    }

    private UserNotifyProfileEntity entity(UUID userId, String prefsJson, boolean active) {
        return new UserNotifyProfileEntity(userId, prefsJson, true, active, null, null, Instant.now());
    }

    @Test
    @DisplayName("저장된 JSON을 NotificationType 맵으로 역직렬화해 투영한다")
    void findByUserId_deserializesPrefs() {
        when(repository.findById(USER_ID))
                .thenReturn(Optional.of(entity(USER_ID, "{\"MARKET_ALERT\":false}", true)));

        TradingUserProfile profile = adapter().findByUserId(USER_ID).orElseThrow();

        assertThat(profile.userId()).isEqualTo(USER_ID);
        assertThat(profile.balanceCheckEnabled()).isTrue();
        assertThat(profile.isNotificationEnabled(NotificationType.MARKET_ALERT)).isFalse();
        assertThat(profile.isNotificationEnabled(NotificationType.TRADING_ALERT)).isTrue(); // 미설정=활성
    }

    @Test
    @DisplayName("복제본이 없으면 빈 Optional — StrategyCreationService의 사용자 존재 확인 의미 유지")
    void findByUserId_emptyWhenMissing() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(adapter().findByUserId(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("findAllActive는 is_active=true 행만 조회한다")
    void findAllActive_readsActiveRowsOnly() {
        when(repository.findAllByActiveTrue()).thenReturn(List.of(entity(USER_ID, "{}", true)));

        assertThat(adapter().findAllActive()).singleElement()
                .extracting(TradingUserProfile::userId).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("JSON이 깨져 있어도 기본값으로 떨어질 뿐 매매 경로를 막지 않는다")
    void corruptedPrefsFallsBackToDefaults() {
        when(repository.findById(USER_ID)).thenReturn(Optional.of(entity(USER_ID, "not-json", true)));

        TradingUserProfile profile = adapter().findByUserId(USER_ID).orElseThrow();

        assertThat(profile.notificationPrefs()).isEmpty();
    }

    @Test
    @DisplayName("배치 조회는 userId 키 맵으로 접는다")
    void findAllByUserIds_mapsById() {
        when(repository.findAllByUserIdIn(List.of(USER_ID)))
                .thenReturn(List.of(entity(USER_ID, "{}", true)));

        assertThat(adapter().findAllByUserIds(List.of(USER_ID))).containsOnlyKeys(USER_ID);
    }

    @Test
    @DisplayName("암호문으로 저장된 telegramBotToken을 복호화해 평문으로 투영한다")
    void findByUserId_decryptsTelegramBotToken() {
        UserNotifyProfileEntity withTelegram = new UserNotifyProfileEntity(
                USER_ID, "{}", true, true, "cipher-text", "chat-1", Instant.now());
        when(repository.findById(USER_ID)).thenReturn(Optional.of(withTelegram));
        when(crypto.decrypt("cipher-text")).thenReturn("plain-token");

        TradingUserProfile profile = adapter().findByUserId(USER_ID).orElseThrow();

        assertThat(profile.telegramBotToken()).isEqualTo("plain-token");
        assertThat(profile.chatId()).isEqualTo("chat-1");
    }

    @Test
    @DisplayName("telegramBotToken이 없으면 복호화를 시도하지 않고 null을 유지한다")
    void findByUserId_nullTelegramBotTokenSkipsDecrypt() {
        when(repository.findById(USER_ID)).thenReturn(Optional.of(entity(USER_ID, "{}", true)));

        TradingUserProfile profile = adapter().findByUserId(USER_ID).orElseThrow();

        assertThat(profile.telegramBotToken()).isNull();
    }
}
