package com.kista.admin.application.service;

import com.kista.account.domain.model.Account;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.account.application.port.output.AccountPort;
import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.strategyconfig.application.port.output.StrategyPort;
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
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

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
            STRATEGY_ID, ACCOUNT_ID, StrategyType.PRIVACY, StrategyStatus.ACTIVE,
            StrategyTicker.SOXL, StrategyCycleSeedType.NONE
    );

    @Test
    void pauseStrategy_updatesStatusAndLogs() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(
                new Account(ACCOUNT_ID, UUID.randomUUID(), "계좌", "12345678", "app", "secret", null, Account.Broker.KIS, null));

        service.pauseStrategy(ADMIN_ID, ACCOUNT_ID, STRATEGY_ID);

        verify(strategyPort).save(argThat(s -> s.id().equals(STRATEGY_ID) && s.status() == StrategyStatus.PAUSED));
        verify(auditLogPort).log(ADMIN_ID, "STRATEGY_PAUSE", "STRATEGY", STRATEGY_ID, Map.of("accountId", ACCOUNT_ID.toString()));
    }

    @Test
    void resumeStrategy_updatesStatusAndLogs() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY.withStatus(StrategyStatus.PAUSED));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(
                new Account(ACCOUNT_ID, UUID.randomUUID(), "계좌", "12345678", "app", "secret", null, Account.Broker.KIS, null));

        service.resumeStrategy(ADMIN_ID, ACCOUNT_ID, STRATEGY_ID);

        verify(strategyPort).save(argThat(s -> s.id().equals(STRATEGY_ID) && s.status() == StrategyStatus.ACTIVE));
        verify(auditLogPort).log(ADMIN_ID, "STRATEGY_RESUME", "STRATEGY", STRATEGY_ID, Map.of("accountId", ACCOUNT_ID.toString()));
    }
}
