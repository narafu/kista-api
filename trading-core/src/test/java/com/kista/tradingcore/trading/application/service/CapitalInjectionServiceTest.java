package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.InjectCapitalCommand;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CapitalInjectionService 단위 테스트")
class CapitalInjectionServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock AccountPort accountPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;

    CapitalInjectionService service;

    private final UUID strategyId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID cycleId = UUID.randomUUID();
    private final UUID latestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CapitalInjectionService(strategyPort, accountPort, strategyCyclePort, cyclePositionPort);
    }

    private void stub(StrategyType type, BigDecimal holdings, BigDecimal avg, LocalDate endDate) {
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(
                new Strategy(strategyId, accountId, type, StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
        lenient(strategyCyclePort.requireLatestByStrategyId(strategyId)).thenReturn(new StrategyCycle(cycleId, strategyId,
                UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.of(2026, 1, 1), endDate, null, null));
        lenient(cyclePositionPort.findLatestOne(cycleId)).thenReturn(Optional.of(new CyclePosition(latestId, cycleId,
                new BigDecimal("500.00"), new BigDecimal("30.00"), avg, holdings, null, null)));
    }

    private static <T> org.mockito.stubbing.OngoingStubbing<T> lenient(T call) {
        return org.mockito.Mockito.lenient().when(call);
    }

    private AccountBalance updatedBalance() {
        ArgumentCaptor<AccountBalance> c = ArgumentCaptor.forClass(AccountBalance.class);
        verify(cyclePositionPort).updateBalance(eq(latestId), c.capture());
        verify(cyclePositionPort, never()).save(any()); // append 금지 — 최신 행만 갱신
        return c.getValue();
    }

    @Test
    void depositOnly_addsDepositAndStartAmount_keepsHoldingsAndAvg() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("300.00"), null, null));

        AccountBalance p = updatedBalance();
        assertThat(p.usdDeposit()).isEqualByComparingTo("800.00");
        assertThat(p.holdings()).isEqualByComparingTo("10");
        assertThat(p.avgPrice()).isEqualByComparingTo("20.0000");
        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("1300.00"));
    }

    @Test
    void sharesOnly_weightedAverage() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        service.inject(strategyId, userId, new InjectCapitalCommand(null, 10, new BigDecimal("30.00")));

        AccountBalance p = updatedBalance();
        assertThat(p.holdings()).isEqualByComparingTo("20");
        assertThat(p.avgPrice()).isEqualByComparingTo("25.0000"); // (20×10 + 30×10) / 20
        assertThat(p.usdDeposit()).isEqualByComparingTo("500.00");
        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("1300.00"));
    }

    @Test
    void sharesFromZeroHoldings_avgEqualsSharePrice() {
        stub(StrategyType.PRIVACY, BigDecimal.ZERO, null, null);

        service.inject(strategyId, userId, new InjectCapitalCommand(null, 4, new BigDecimal("12.5000")));

        AccountBalance p = updatedBalance();
        assertThat(p.holdings()).isEqualByComparingTo("4");
        assertThat(p.avgPrice()).isEqualByComparingTo("12.5000");
    }

    @Test
    void depositAndShares_bothApplied_startAmountIncludesBoth() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("100.00"), 5, new BigDecimal("40.00")));

        AccountBalance p = updatedBalance();
        assertThat(p.usdDeposit()).isEqualByComparingTo("600.00");
        assertThat(p.holdings()).isEqualByComparingTo("15");
        assertThat(p.avgPrice()).isEqualByComparingTo("26.6667"); // (200 + 200) / 15
        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("1300.00")); // 1000 + 100 + 5×40
    }

    @Test
    void infinite_updatesInPlace_noAppendNoDetailCopy() {
        stub(StrategyType.INFINITE, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("100.00"), null, null));

        updatedBalance();
        verify(cyclePositionPort, never()).save(any());
    }

    @Test
    void usesFreshCycleStartAmountAfterLock() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        StrategyCycle stale = strategyCyclePort.requireLatestByStrategyId(strategyId);
        StrategyCycle fresh = new StrategyCycle(cycleId, strategyId, stale.strategyVersionId(),
                new BigDecimal("1500.00"), null, stale.startDate(), null, null, null);
        when(strategyCyclePort.requireLatestByStrategyId(strategyId)).thenReturn(stale, fresh);

        service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("100.00"), null, null));

        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("1600.00"));
    }

    @Test
    void rejects_cycleEndedWhileWaitingForLock() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        StrategyCycle open = strategyCyclePort.requireLatestByStrategyId(strategyId);
        StrategyCycle ended = new StrategyCycle(cycleId, strategyId, open.strategyVersionId(),
                open.startAmount(), null, open.startDate(), LocalDate.of(2026, 2, 1), null, null);
        when(strategyCyclePort.requireLatestByStrategyId(strategyId)).thenReturn(open, ended);

        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(BigDecimal.ONE, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("진행 중인 사이클이 없어 투자금을 추가할 수 없습니다.");
        verify(cyclePositionPort, never()).updateBalance(any(), any());
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
    }

    @Test
    void locksCycleBeforeSaving() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("100.00"), null, null));

        InOrder order = inOrder(strategyCyclePort, cyclePositionPort);
        order.verify(strategyCyclePort).lockForUpdate(cycleId);
        order.verify(cyclePositionPort).updateBalance(eq(latestId), any());
    }

    @Test
    void rejects_nothingGiven() {
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(
                new Strategy(strategyId, accountId, StrategyType.PRIVACY, StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("추가할 예수금 또는 주식을 입력해야 합니다.");
    }

    @Test
    void rejects_sharesWithoutPrice_andPriceWithoutShares() {
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(
                new Strategy(strategyId, accountId, StrategyType.PRIVACY, StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(null, 3, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("주식을 추가하려면 수량과 매수단가를 함께 입력해야 합니다.");
        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(BigDecimal.ONE, null, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("주식을 추가하려면 수량과 매수단가를 함께 입력해야 합니다.");
    }

    @Test
    void rejects_nonPositiveValues() {
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(
                new Strategy(strategyId, accountId, StrategyType.PRIVACY, StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("-1"), null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("추가 금액과 수량은 0보다 커야 합니다.");
        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(null, 0, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("추가 금액과 수량은 0보다 커야 합니다.");
    }

    @Test
    void rejects_vrAndDca() {
        for (StrategyType type : List.of(StrategyType.VR, StrategyType.DCA)) {
            when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(
                    new Strategy(strategyId, accountId, type, StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE));
            assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(BigDecimal.ONE, null, null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("투자금 추가는 PRIVACY·INFINITE 전략만 지원합니다.");
        }
        verify(cyclePositionPort, never()).save(any());
    }

    @Test
    void rejects_endedCycle() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), LocalDate.of(2026, 2, 1));
        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(BigDecimal.ONE, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("진행 중인 사이클이 없어 투자금을 추가할 수 없습니다.");
        verify(strategyCyclePort, never()).lockForUpdate(any());
    }
}
