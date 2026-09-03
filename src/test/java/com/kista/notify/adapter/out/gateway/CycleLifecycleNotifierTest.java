package com.kista.notify.adapter.out.gateway;

import com.kista.account.application.port.output.AccountPort;
import com.kista.user.application.port.output.UserPort;
import com.kista.trading.application.event.CycleCompletedEvent;
import com.kista.trading.application.event.NewCycleStartedEvent;
import com.kista.account.domain.model.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.user.domain.model.User;
import com.kista.notify.application.port.output.UserNotificationPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CycleLifecycleNotifierTest {

    @Mock UserNotificationPort userNotificationPort;
    @Mock UserPort userPort;
    @Mock AccountPort accountPort;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), USER_ID);
    private static final User USER = DomainFixtures.activeUserWithTelegram(USER_ID);
    private static final Strategy STRATEGY = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAINTAIN);

    @Test
    void onCycleCompleted_notifiesUserOfCycleCompletion() {
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(USER);
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        CycleLifecycleNotifier notifier = new CycleLifecycleNotifier(userNotificationPort, userPort, accountPort);
        CycleCompletedEvent event = new CycleCompletedEvent(USER_ID, ACCOUNT.id(), STRATEGY);

        notifier.onCycleCompleted(event);

        verify(userNotificationPort).notifyCycleCompleted(USER, ACCOUNT, STRATEGY);
    }

    @Test
    void onNewCycleStarted_notifiesUserOfNewCycle() {
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(USER);
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        CycleLifecycleNotifier notifier = new CycleLifecycleNotifier(userNotificationPort, userPort, accountPort);
        BigDecimal initialUsdDeposit = new BigDecimal("1000.00");
        NewCycleStartedEvent event = new NewCycleStartedEvent(USER_ID, ACCOUNT.id(), STRATEGY, initialUsdDeposit);

        notifier.onNewCycleStarted(event);

        verify(userNotificationPort).notifyNewCycleStarted(USER, ACCOUNT, STRATEGY, initialUsdDeposit);
    }
}
