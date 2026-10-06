package com.kista.trading.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import com.kista.sharedkernel.NotificationChannel;
import tools.jackson.databind.ObjectMapper;
import com.kista.platform.crypto.AesCryptoService;
import com.kista.sharedkernel.NotificationType;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
