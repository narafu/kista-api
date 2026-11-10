package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.support.FixedClockTestConfig;
import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.StrategyDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyDetailAssemblerTest {

    @Mock StrategyVersionPort strategyVersionPort;
    @Mock StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    @Mock VrStrategyDetailService vrStrategyDetailService;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    @Mock StrategyDcaDetailPort dcaDetailPort;
    @Mock DcaExecutionPort dcaExecutionPort;
    @Mock DcaSummaryAssembler dcaSummaryAssembler;

    private final Strategy strategy = new Strategy(UUID.randomUUID(), UUID.randomUUID(), StrategyType.DCA, StrategyStatus.ACTIVE,
            StrategyTicker.BTC, StrategyCycleSeedType.NONE);

    private StrategyDetailAssembler assembler() {
        return new StrategyDetailAssembler(strategyVersionPort, strategyInfiniteDetailPort, vrStrategyDetailService,
                strategyCyclePort, cyclePositionPort, cyclePositionInfiniteDetailPort, dcaDetailPort, dcaExecutionPort,
                dcaSummaryAssembler, FixedClockTestConfig.FIXED);
    }

    @Test
    void krwDetail_roundsDeposits() {
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), new BigDecimal("50000.00"), null,
                LocalDate.parse("2026-06-01"), null, Instant.parse("2026-06-01T00:00:00Z"), null);
        when(strategyCyclePort.findLatestByStrategyId(strategy.id())).thenReturn(Optional.of(cycle));
        when(cyclePositionPort.findLatestOneByStrategyId(strategy.id())).thenReturn(Optional.of(
                new CyclePosition(UUID.randomUUID(), cycle.id(), new BigDecimal("12345.67"), null, null, BigDecimal.ZERO, Instant.now(), null)));

        StrategyDetail detail = assembler().toDetail(strategy, Market.CRYPTO_KRW);

        assertThat(detail.initialDeposit()).hasToString("50000");
        assertThat(detail.ledgerDeposit()).hasToString("12346");
    }

    @Test
    void krwDetail_withoutPosition_keepsNullDeposits() {
        when(strategyCyclePort.findLatestByStrategyId(strategy.id())).thenReturn(Optional.empty());
        when(cyclePositionPort.findLatestOneByStrategyId(strategy.id())).thenReturn(Optional.empty());

        StrategyDetail detail = assembler().toDetail(strategy, Market.CRYPTO_KRW);

        assertThat(detail.initialDeposit()).isNull();
        assertThat(detail.ledgerDeposit()).isNull();
    }
}
