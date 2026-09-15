package com.kista.trading.notify.adapter.out.gateway;

import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.notify.application.port.output.TradingUserNotificationPort;
import com.kista.sharedkernel.CycleCompletedEvent;
import com.kista.sharedkernel.NewCycleStartedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
class CycleLifecycleNotifierTest {

    @Mock TradingUserNotificationPort userNotificationPort;
    @Mock TradingUserProfilePort userProfilePort;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final String ACCOUNT_NICKNAME = "테스트계좌";
    private static final TradingUserProfile PROFILE = new TradingUserProfile(USER_ID, Map.of(), true, "token", "chat");

    @Test
    void onCycleCompleted_notifiesUserOfCycleCompletion() {
        when(userProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(PROFILE));
        CycleLifecycleNotifier notifier = new CycleLifecycleNotifier(userNotificationPort, userProfilePort);
        CycleCompletedEvent event = new CycleCompletedEvent(USER_ID, ACCOUNT_ID, ACCOUNT_NICKNAME,
                StrategyType.INFINITE, StrategyTicker.SOXL, StrategyCycleSeedType.MAINTAIN);

        notifier.onCycleCompleted(event);

        verify(userNotificationPort).notifyCycleCompleted(PROFILE, ACCOUNT_NICKNAME,
                StrategyType.INFINITE, StrategyTicker.SOXL, StrategyCycleSeedType.MAINTAIN);
    }

    @Test
    void onNewCycleStarted_notifiesUserOfNewCycle() {
        when(userProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(PROFILE));
        CycleLifecycleNotifier notifier = new CycleLifecycleNotifier(userNotificationPort, userProfilePort);
        BigDecimal initialUsdDeposit = new BigDecimal("1000.00");
        NewCycleStartedEvent event = new NewCycleStartedEvent(USER_ID, ACCOUNT_ID, ACCOUNT_NICKNAME,
                StrategyType.INFINITE, StrategyTicker.SOXL, initialUsdDeposit);

        notifier.onNewCycleStarted(event);

        verify(userNotificationPort).notifyNewCycleStarted(PROFILE, ACCOUNT_NICKNAME,
                StrategyType.INFINITE, StrategyTicker.SOXL, initialUsdDeposit);
    }
}
