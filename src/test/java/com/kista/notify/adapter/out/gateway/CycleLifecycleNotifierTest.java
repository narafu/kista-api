package com.kista.notify.adapter.out.gateway;

import com.kista.application.event.CycleCompletedEvent;
import com.kista.application.event.NewCycleStartedEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.notify.domain.port.out.UserNotificationPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CycleLifecycleNotifierTest {

    @Mock UserNotificationPort userNotificationPort;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), USER_ID);
    private static final User USER = DomainFixtures.activeUserWithTelegram(USER_ID);
    private static final Strategy STRATEGY = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAINTAIN);

    @Test
    void onCycleCompleted_notifiesUserOfCycleCompletion() {
        CycleLifecycleNotifier notifier = new CycleLifecycleNotifier(userNotificationPort);
        CycleCompletedEvent event = new CycleCompletedEvent(USER, ACCOUNT, STRATEGY);

        notifier.onCycleCompleted(event);

        verify(userNotificationPort).notifyCycleCompleted(USER, ACCOUNT, STRATEGY);
    }

    @Test
    void onNewCycleStarted_notifiesUserOfNewCycle() {
        CycleLifecycleNotifier notifier = new CycleLifecycleNotifier(userNotificationPort);
        BigDecimal initialUsdDeposit = new BigDecimal("1000.00");
        NewCycleStartedEvent event = new NewCycleStartedEvent(USER, ACCOUNT, STRATEGY, initialUsdDeposit);

        notifier.onNewCycleStarted(event);

        verify(userNotificationPort).notifyNewCycleStarted(USER, ACCOUNT, STRATEGY, initialUsdDeposit);
    }
}
