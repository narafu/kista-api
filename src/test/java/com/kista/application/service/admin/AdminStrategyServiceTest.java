package com.kista.application.service.admin;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.StrategyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminStrategyServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock AccountPort accountPort;
    @Mock AuditLogPort auditLogPort;

    @InjectMocks AdminStrategyService service;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final Strategy STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, Strategy.Type.PRIVACY, Strategy.Status.ACTIVE,
            Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
    );

    @Test
    void pauseStrategy_updatesStatusAndLogs() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(
                new Account(ACCOUNT_ID, UUID.randomUUID(), "계좌", "12345678", "app", "secret", null, Account.Broker.KIS, null));

        service.pauseStrategy(ADMIN_ID, ACCOUNT_ID, STRATEGY_ID);

        verify(strategyPort).save(argThat(s -> s.id().equals(STRATEGY_ID) && s.status() == Strategy.Status.PAUSED));
        verify(auditLogPort).log(ADMIN_ID, "STRATEGY_PAUSE", "STRATEGY", STRATEGY_ID, Map.of("accountId", ACCOUNT_ID.toString()));
    }

    @Test
    void resumeStrategy_updatesStatusAndLogs() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY.withStatus(Strategy.Status.PAUSED));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(
                new Account(ACCOUNT_ID, UUID.randomUUID(), "계좌", "12345678", "app", "secret", null, Account.Broker.KIS, null));

        service.resumeStrategy(ADMIN_ID, ACCOUNT_ID, STRATEGY_ID);

        verify(strategyPort).save(argThat(s -> s.id().equals(STRATEGY_ID) && s.status() == Strategy.Status.ACTIVE));
        verify(auditLogPort).log(ADMIN_ID, "STRATEGY_RESUME", "STRATEGY", STRATEGY_ID, Map.of("accountId", ACCOUNT_ID.toString()));
    }
}
