package com.kista.tradingcore.trading.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import com.kista.shared.kernel.NotificationChannel;
import tools.jackson.databind.ObjectMapper;
import com.kista.shared.platform.crypto.AesCryptoService;
import com.kista.shared.kernel.NotificationType;
import com.kista.shared.kernel.UserNotifyProfileChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserNotifyProfileSyncListener 단위 테스트")
class UserNotifyProfileSyncListenerTest {

    @Mock UserNotifyProfileJpaRepository repository;
    @Mock AesCryptoService crypto;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID USER_ID = UUID.randomUUID();

    private UserNotifyProfileSyncListener listener() {
        return new UserNotifyProfileSyncListener(repository, objectMapper, crypto);
    }

    @Test
    @DisplayName("프로필 변경 이벤트를 받으면 복제본 행을 upsert 한다")
    void onProfileChanged_upsertsRow() {
        listener().onProfileChanged(new UserNotifyProfileChangedEvent(
                USER_ID, Map.of(NotificationType.MARKET_ALERT, false), false, true, null, null));

        ArgumentCaptor<UserNotifyProfileEntity> captor = ArgumentCaptor.forClass(UserNotifyProfileEntity.class);
        verify(repository).save(captor.capture());
        UserNotifyProfileEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.isBalanceCheckEnabled()).isFalse();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getNotificationPrefsJson()).isEqualTo("{\"MARKET_ALERT\":false}");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("알림 채널을 복제한다")
    void onProfileChanged_storesNotificationChannel() {
        listener().onProfileChanged(new UserNotifyProfileChangedEvent(
                USER_ID, Map.of(), true, true, null, null, NotificationChannel.FCM));

        ArgumentCaptor<UserNotifyProfileEntity> captor = ArgumentCaptor.forClass(UserNotifyProfileEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getNotificationChannel()).isEqualTo(NotificationChannel.FCM);
    }

    @Test
    @DisplayName("채널 없는 이벤트(구버전 root 발행)는 저장된 채널을 지우지 않는다")
    void onProfileChanged_nullChannelKeepsStoredChannel() {
        when(repository.findById(USER_ID)).thenReturn(Optional.of(new UserNotifyProfileEntity(
                USER_ID, "{}", true, true, null, null, Instant.now(), NotificationChannel.NONE)));

        listener().onProfileChanged(new UserNotifyProfileChangedEvent(USER_ID, Map.of(), true, true, "t", "c"));

        ArgumentCaptor<UserNotifyProfileEntity> captor = ArgumentCaptor.forClass(UserNotifyProfileEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getNotificationChannel()).isEqualTo(NotificationChannel.NONE);
    }

    @Test
    @DisplayName("스트림 payload는 채널 필드가 있든 없든(구버전) 역직렬화된다")
    void event_deserializesWithAndWithoutChannel() {
        var withChannel = new UserNotifyProfileChangedEvent(USER_ID, Map.of(), true, true, "t", "c", NotificationChannel.ALL);
        String json = objectMapper.writeValueAsString(withChannel);
        assertThat(objectMapper.readValue(json, UserNotifyProfileChangedEvent.class)).isEqualTo(withChannel);

        String legacy = json.replace(",\"notificationChannel\":\"ALL\"", "");
        assertThat(legacy).doesNotContain("notificationChannel");
        assertThat(objectMapper.readValue(legacy, UserNotifyProfileChangedEvent.class).notificationChannel()).isNull();
    }

    @Test
    @DisplayName("복제본보다 옛 스냅샷(재시도·재발행)은 덮어쓰지 않는다")
    void onProfileChanged_skipsOlderSnapshot() {
        Instant stored = Instant.parse("2026-10-16T10:00:00Z");
        when(repository.findById(USER_ID)).thenReturn(Optional.of(new UserNotifyProfileEntity(
                USER_ID, "{}", true, false, null, null, stored, NotificationChannel.NONE)));

        listener().onProfileChanged(new UserNotifyProfileChangedEvent(
                USER_ID, Map.of(), true, true, null, null, NotificationChannel.NONE, stored.minusSeconds(1)));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("더 새로운 스냅샷은 적용하고 updated_at에 스냅샷 시각을 버전으로 기록한다")
    void onProfileChanged_appliesNewerSnapshotWithVersion() {
        Instant stored = Instant.parse("2026-10-16T10:00:00Z");
        Instant occurredAt = stored.plusSeconds(1);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(new UserNotifyProfileEntity(
                USER_ID, "{}", true, true, null, null, stored, NotificationChannel.NONE)));

        listener().onProfileChanged(new UserNotifyProfileChangedEvent(
                USER_ID, Map.of(), true, false, null, null, NotificationChannel.NONE, occurredAt));

        ArgumentCaptor<UserNotifyProfileEntity> captor = ArgumentCaptor.forClass(UserNotifyProfileEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("occurredAt 없는 구버전 스냅샷은 비교 없이 적용한다")
    void onProfileChanged_legacySnapshotWithoutVersionIsApplied() {
        when(repository.findById(USER_ID)).thenReturn(Optional.of(new UserNotifyProfileEntity(
                USER_ID, "{}", true, true, null, null, Instant.now().plusSeconds(3600), NotificationChannel.NONE)));

        listener().onProfileChanged(new UserNotifyProfileChangedEvent(USER_ID, Map.of(), true, false, null, null));

        verify(repository).save(any());
    }

    @Test
    @DisplayName("스트림 payload의 occurredAt이 역직렬화된다")
    void event_deserializesOccurredAt() {
        var event = new UserNotifyProfileChangedEvent(USER_ID, Map.of(), true, true, null, null,
                NotificationChannel.ALL, Instant.parse("2026-10-16T10:00:00Z"));
        assertThat(objectMapper.readValue(objectMapper.writeValueAsString(event), UserNotifyProfileChangedEvent.class)).isEqualTo(event);
    }

    @Test
    @DisplayName("비활성 전환 이벤트는 is_active=false로 기록돼 장 이벤트 브로드캐스트에서 빠진다")
    void onProfileChanged_inactiveIsPersisted() {
        listener().onProfileChanged(new UserNotifyProfileChangedEvent(USER_ID, Map.of(), true, false, null, null));

        ArgumentCaptor<UserNotifyProfileEntity> captor = ArgumentCaptor.forClass(UserNotifyProfileEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    @DisplayName("telegramBotToken 평문을 암호화해 저장하고, DB 저장값은 평문이 아니다")
    void onProfileChanged_encryptsTelegramBotToken() {
        when(crypto.encrypt("plain-token")).thenReturn("cipher-text");

        listener().onProfileChanged(new UserNotifyProfileChangedEvent(
                USER_ID, Map.of(), true, true, "plain-token", "chat-1"));

        ArgumentCaptor<UserNotifyProfileEntity> captor = ArgumentCaptor.forClass(UserNotifyProfileEntity.class);
        verify(repository).save(captor.capture());
        UserNotifyProfileEntity saved = captor.getValue();
        assertThat(saved.getTelegramBotToken()).isEqualTo("cipher-text").isNotEqualTo("plain-token");
        assertThat(saved.getChatId()).isEqualTo("chat-1");
    }

    @Test
    @DisplayName("telegramBotToken이 null이면 암호화를 시도하지 않고 null을 저장한다")
    void onProfileChanged_nullTelegramBotTokenSkipsEncrypt() {
        listener().onProfileChanged(new UserNotifyProfileChangedEvent(USER_ID, Map.of(), true, true, null, null));

        ArgumentCaptor<UserNotifyProfileEntity> captor = ArgumentCaptor.forClass(UserNotifyProfileEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTelegramBotToken()).isNull();
    }
}
