package com.kista.user.application.service;

import com.kista.sharedkernel.NotificationType;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import com.kista.sharedkernel.UserStatus;
import com.kista.support.DomainFixtures;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.domain.model.User;
import com.kista.user.domain.model.UserSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserNotifyProfilePublisher 단위 테스트")
class UserNotifyProfilePublisherTest {

    @Mock UserPort userPort;
    @Mock UserSettingsPort userSettingsPort;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks UserNotifyProfilePublisher publisher;

    private final UUID USER_ID = UUID.randomUUID();

    private UserNotifyProfileChangedEvent captured() {
        ArgumentCaptor<UserNotifyProfileChangedEvent> captor =
                ArgumentCaptor.forClass(UserNotifyProfileChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("상태 변경 발행 시 ACTIVE 여부와 조회한 설정을 함께 싣는다")
    void publishStatusChanged_carriesActiveFlagAndSettings() {
        User user = DomainFixtures.userWithStatus(USER_ID, UserStatus.ACTIVE, (Instant) null);
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(
                new UserSettings(USER_ID, false, Map.of(NotificationType.MARKET_ALERT, false),
                        UserSettings.DEFAULT_STRATEGY_SUGGESTIONS));

        publisher.publishStatusChanged(user);

        UserNotifyProfileChangedEvent event = captured();
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.active()).isTrue();
        assertThat(event.balanceCheckEnabled()).isFalse();
        assertThat(event.notificationPrefs()).containsEntry(NotificationType.MARKET_ALERT, false);
    }

    @Test
    @DisplayName("ACTIVE가 아닌 상태는 active=false로 발행해 장 이벤트 브로드캐스트에서 빠진다")
    void publishStatusChanged_nonActiveStatusIsInactive() {
        User user = DomainFixtures.userWithStatus(USER_ID, UserStatus.REJECTED, (Instant) null);
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));

        publisher.publishStatusChanged(user);

        assertThat(captured().active()).isFalse();
    }

    @Test
    @DisplayName("설정 변경 발행 시 현재 사용자 상태를 조회해 active를 채운다")
    void publishSettingsChanged_looksUpStatus() {
        when(userPort.findById(USER_ID)).thenReturn(
                Optional.of(DomainFixtures.userWithStatus(USER_ID, UserStatus.ACTIVE, (Instant) null)));

        publisher.publishSettingsChanged(UserSettings.defaultFor(USER_ID));

        assertThat(captured().active()).isTrue();
    }

    @Test
    @DisplayName("소프트 삭제 등으로 사용자를 찾을 수 없으면 active=false로 발행한다")
    void publishSettingsChanged_missingUserIsInactive() {
        when(userPort.findById(USER_ID)).thenReturn(Optional.empty());

        publisher.publishSettingsChanged(UserSettings.defaultFor(USER_ID));

        assertThat(captured().active()).isFalse();
    }
}
