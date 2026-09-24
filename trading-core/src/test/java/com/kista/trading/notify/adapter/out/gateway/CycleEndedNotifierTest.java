package com.kista.trading.notify.adapter.out.gateway;

import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.notify.application.port.output.TradingUserNotificationPort;
import com.kista.sharedkernel.CycleEndedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
class CycleEndedNotifierTest {

    @Mock TradingUserNotificationPort userNotificationPort;
    @Mock TradingUserProfilePort userProfilePort;

    @Test
    void onCycleEnded_notifiesUserOfCycleCompletion() {
        CycleEndedNotifier notifier = new CycleEndedNotifier(userNotificationPort, userProfilePort);
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        TradingUserProfile profile = new TradingUserProfile(userId, Map.of(), true, "token", "chat");
        when(userProfilePort.findByUserId(userId)).thenReturn(Optional.of(profile));
        CycleEndedEvent event = new CycleEndedEvent(userId, accountId, "테스트계좌",
                StrategyType.PRIVACY, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);

        notifier.onCycleEnded(event);

        verify(userNotificationPort).notifyCycleCompleted(profile, "테스트계좌",
                StrategyType.PRIVACY, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }

    // TradingUserProfiles.requireProfile 추출 후에도 profile 미존재 시 예외 트리거 조건이 그대로인지 검증
    @Test
    void onCycleEnded_throwsWhenProfileMissing() {
        CycleEndedNotifier notifier = new CycleEndedNotifier(userNotificationPort, userProfilePort);
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(userProfilePort.findByUserId(userId)).thenReturn(Optional.empty());
        CycleEndedEvent event = new CycleEndedEvent(userId, accountId, "테스트계좌",
                StrategyType.PRIVACY, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);

        assertThatThrownBy(() -> notifier.onCycleEnded(event))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("user_notify_profile 없음: " + userId);
        verifyNoInteractions(userNotificationPort);
    }
}
