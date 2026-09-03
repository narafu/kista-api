package com.kista.notify.adapter.out.gateway;

import com.kista.trading.application.event.CycleEndedEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.notify.application.port.output.UserNotificationPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CycleEndedNotifierTest {

    @Mock UserNotificationPort userNotificationPort;

    @Test
    void onCycleEnded_notifiesUserOfCycleCompletion() {
        CycleEndedNotifier notifier = new CycleEndedNotifier(userNotificationPort);
        UUID userId = UUID.randomUUID();
        Account account = DomainFixtures.kisAccount(UUID.randomUUID(), userId);
        User user = DomainFixtures.activeUserWithTelegram(userId);
        Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.PRIVACY,
                Strategy.Status.PAUSED, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        CycleEndedEvent event = new CycleEndedEvent(user, account, strategy);

        notifier.onCycleEnded(event);

        verify(userNotificationPort).notifyCycleCompleted(user, account, strategy);
    }
}
