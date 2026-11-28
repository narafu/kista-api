package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.support.FixedClockTestConfig;
import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.StrategyAction;
import com.kista.tradingcore.trading.domain.model.StrategyActionJudgment;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
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
            StrategyTicker.UPRO, StrategyCycleSeedType.NONE);

    private StrategyDetailAssembler assembler() {
        return new StrategyDetailAssembler(strategyVersionPort, strategyInfiniteDetailPort, vrStrategyDetailService,
                strategyCyclePort, cyclePositionPort, cyclePositionInfiniteDetailPort, dcaDetailPort, dcaExecutionPort,
                dcaSummaryAssembler, FixedClockTestConfig.FIXED);
    }

    @Test
    void detail_withoutPosition_keepsNullDeposits() {
        when(strategyCyclePort.findLatestByStrategyId(strategy.id())).thenReturn(Optional.empty());
        when(cyclePositionPort.findLatestOneByStrategyId(strategy.id())).thenReturn(Optional.empty());

        StrategyDetail detail = assembler().toDetail(strategy, Currency.USD);

        assertThat(detail.initialDeposit()).isNull();
        assertThat(detail.ledgerDeposit()).isNull();
    }

    @Test
    void detail_ledgerDepositIsFlooredAndJudgedOnFlooredValue() {
        // 진행 중 사이클 + 장부 예수금 0.009 — 반올림이면 0.01로 보여 인출 가능처럼 보이지만 실제 인출 가능한 센트는 없다
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), new BigDecimal("1000"), null,
                LocalDate.of(2026, 11, 2), null, Instant.EPOCH, null);
        when(strategyCyclePort.findLatestByStrategyId(strategy.id())).thenReturn(Optional.of(cycle));
        when(cyclePositionPort.findLatestOneByStrategyId(strategy.id())).thenReturn(Optional.of(
                new CyclePosition(UUID.randomUUID(), cycle.id(), new BigDecimal("0.009"), null, null, BigDecimal.ZERO, Instant.EPOCH, null)));

        StrategyDetail detail = assembler().toDetail(strategy, Currency.USD);

        assertThat(detail.ledgerDeposit()).isEqualTo(new BigDecimal("0.00")); // 내림, isEqualTo는 scale까지 비교
        assertThat(StrategyActionJudgment.of(detail).blocked())
                .contains(new StrategyActionJudgment.Blocked(StrategyAction.WITHDRAW_CAPITAL, StrategyAction.BlockReason.NO_WITHDRAWABLE_BALANCE));
    }

    @Test
    void detail_archivedVr_passesNoRolloverBase() {
        // 보관 VR은 롤오버가 다시 오지 않으므로 nextRolloverDate 기준일을 넘기지 않는다(지난 예정일 노출 방지)
        Strategy archivedVr = new Strategy(UUID.randomUUID(), UUID.randomUUID(), StrategyType.VR, StrategyStatus.ARCHIVED,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), archivedVr.id(), new BigDecimal("1000"), null,
                LocalDate.of(2026, 11, 2), null, Instant.EPOCH, null);
        CyclePosition opening = new CyclePosition(UUID.randomUUID(), cycle.id(), new BigDecimal("1000"), null, null, BigDecimal.ZERO, Instant.EPOCH, null);
        when(strategyCyclePort.findLatestByStrategyId(archivedVr.id())).thenReturn(Optional.of(cycle));
        when(cyclePositionPort.findFirstOne(cycle.id())).thenReturn(Optional.of(opening));
        when(cyclePositionPort.findLatestOneByStrategyId(archivedVr.id())).thenReturn(Optional.of(opening));

        assembler().toDetail(archivedVr, Currency.USD);

        verify(vrStrategyDetailService).findSummary(any(), any(), any(), any(), isNull());
    }
}
