package com.kista.tradingcore.trading.application.port.output;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.domain.model.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.UUID;

import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

// StrategyPort.pause default 검증 — default 본문 실행을 위해 CALLS_REAL_METHODS(testing.md "Mockito + interface default 메서드 주의")
@Execution(ExecutionMode.SAME_THREAD)
class StrategyPortTest {

    private final StrategyPort strategyPort = mock(StrategyPort.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

    private static Strategy strategy(StrategyStatus status) {
        return new Strategy(UUID.randomUUID(), UUID.randomUUID(), StrategyType.INFINITE, status,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }

    @Test
    void pause_default_does_not_save_archived() {
        Strategy archived = strategy(StrategyStatus.ARCHIVED);
        doReturn(archived).when(strategyPort).findByIdOrThrow(archived.id()); // 실제 default 호출 방지

        strategyPort.pause(archived.id());

        verify(strategyPort, never()).save(any());
    }

    @Test
    void pause_default_saves_active_as_paused() {
        Strategy active = strategy(StrategyStatus.ACTIVE);
        doReturn(active).when(strategyPort).findByIdOrThrow(active.id());

        strategyPort.pause(active.id());

        verify(strategyPort).save(active.withStatus(StrategyStatus.PAUSED));
    }
}
