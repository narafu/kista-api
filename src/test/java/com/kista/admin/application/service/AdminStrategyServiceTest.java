package com.kista.admin.application.service;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.TradingCommandPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import com.kista.sharedkernel.StrategyStatus;

@ExtendWith(MockitoExtension.class)
class AdminStrategyServiceTest {

    @Mock TradingCommandPort tradingCommandPort;
    @Mock AuditLogPort auditLogPort;

    @InjectMocks AdminStrategyService service;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();

    @Test
    void pauseStrategy_updatesStatusAndLogs() {
        service.pauseStrategy(ADMIN_ID, ACCOUNT_ID, STRATEGY_ID);

        verify(tradingCommandPort).updateStrategyStatus(ACCOUNT_ID, STRATEGY_ID, StrategyStatus.PAUSED);
        verify(auditLogPort).log(ADMIN_ID, "STRATEGY_PAUSE", "STRATEGY", STRATEGY_ID, Map.of("accountId", ACCOUNT_ID.toString()));
    }

    @Test
    void resumeStrategy_updatesStatusAndLogs() {
        service.resumeStrategy(ADMIN_ID, ACCOUNT_ID, STRATEGY_ID);

        verify(tradingCommandPort).updateStrategyStatus(ACCOUNT_ID, STRATEGY_ID, StrategyStatus.ACTIVE);
        verify(auditLogPort).log(ADMIN_ID, "STRATEGY_RESUME", "STRATEGY", STRATEGY_ID, Map.of("accountId", ACCOUNT_ID.toString()));
    }
}
