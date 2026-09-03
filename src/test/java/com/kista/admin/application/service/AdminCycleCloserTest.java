package com.kista.admin.application.service;

import com.kista.trading.domain.model.AccountBalance;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.strategyconfig.application.port.output.StrategyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCycleCloser 단위 테스트")
class AdminCycleCloserTest {

    @Mock
    private StrategyCyclePort strategyCyclePort;
    @Mock
    private StrategyPort strategyPort;

    private final UUID strategyId = UUID.randomUUID();
    private final UUID cycleId = UUID.randomUUID();

    private Strategy activeStrategy() {
        return new Strategy(strategyId, UUID.randomUUID(), StrategyType.INFINITE, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }

    private StrategyCycle currentCycle() {
        return new StrategyCycle(cycleId, strategyId, UUID.randomUUID(), BigDecimal.valueOf(1000),
                null, LocalDate.now(), null, null, null);
    }

    @Test
    @DisplayName("holdings==0이면 사이클을 종료하고 전략을 PAUSED로 저장한다")
    void closeIfExhausted_holdingsZero_endsCycle() {
        Strategy strategy = activeStrategy();
        StrategyCycle cycle = currentCycle();
        AccountBalance balance = new AccountBalance(0, null, BigDecimal.valueOf(500));
        LocalDate tradeDate = LocalDate.of(2026, 7, 17);
        Strategy pausedStrategy = strategy.withStatus(StrategyStatus.PAUSED);
        when(strategyPort.save(strategy.withStatus(StrategyStatus.PAUSED))).thenReturn(pausedStrategy);

        AdminCycleCloser.CycleEndResult result = AdminCycleCloser.closeIfExhausted(
                strategyCyclePort, strategyPort, strategy, cycle, balance, tradeDate);

        // 사이클 종료 기록 — 종료금액=usdDeposit, 종료일자=tradeDate
        verify(strategyCyclePort).markEnded(cycle.id(), balance.usdDeposit(), tradeDate);
        ArgumentCaptor<Strategy> savedCaptor = ArgumentCaptor.forClass(Strategy.class);
        verify(strategyPort).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().status()).isEqualTo(StrategyStatus.PAUSED);
        assertThat(result.strategy()).isEqualTo(pausedStrategy);
        assertThat(result.ended()).isTrue();
        assertThat(result.endDate()).isEqualTo(tradeDate);
    }

    @Test
    @DisplayName("holdings>0이면 포트를 호출하지 않고 원본 전략을 그대로 반환한다")
    void closeIfExhausted_holdingsPositive_noOp() {
        Strategy strategy = activeStrategy();
        StrategyCycle cycle = currentCycle();
        AccountBalance balance = new AccountBalance(5, BigDecimal.TEN, BigDecimal.valueOf(500));
        LocalDate tradeDate = LocalDate.of(2026, 7, 17);

        AdminCycleCloser.CycleEndResult result = AdminCycleCloser.closeIfExhausted(
                strategyCyclePort, strategyPort, strategy, cycle, balance, tradeDate);

        verifyNoInteractions(strategyCyclePort, strategyPort);
        assertThat(result.strategy()).isEqualTo(strategy);
        assertThat(result.ended()).isFalse();
        assertThat(result.endDate()).isNull();
    }
}
