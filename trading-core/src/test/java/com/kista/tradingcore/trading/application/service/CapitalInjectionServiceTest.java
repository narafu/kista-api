package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.support.FixedClockTestConfig;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.InjectCapitalCommand;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.WithdrawCapitalCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
@DisplayName("CapitalInjectionService 단위 테스트 (추가·인출)")
class CapitalInjectionServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock AccountPort accountPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock OrderPort orderPort;
    @Mock TradingBatchRunPort batchRunPort;
    @Mock CycleOrderStrategies cycleOrderStrategies;

    CapitalInjectionService service;

    private final UUID strategyId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID cycleId = UUID.randomUUID();
    private final UUID latestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = newService(FixedClockTestConfig.FIXED);
    }

    private CapitalInjectionService newService(Clock clock) {
        return new CapitalInjectionService(strategyPort, accountPort, strategyCyclePort, cyclePositionPort, orderPort, batchRunPort,
                new StrategyLedgerBudget(cyclePositionPort, orderPort, cycleOrderStrategies), clock);
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

    // ===== 투자금 인출 — 기본 stub: usdDeposit 500.00, startAmount 1000.00, FIXED 시계(오늘 2026-01-06, 다음 거래일 2026-01-07) =====
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 6);
    private static final LocalDate NEXT = LocalDate.of(2026, 1, 7);

    private Order order(LocalDate date, OrderDirection direction, String price, String quantity) {
        return new Order(null, accountId, cycleId, date, StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE, direction,
                new BigDecimal(quantity), new BigDecimal(price), OrderStatus.PLANNED, null, null, null);
    }

    private Order cancelled(LocalDate date, OrderDirection direction, String price, String quantity, String externalOrderId, String leg) {
        return new Order(null, accountId, cycleId, date, StrategyTicker.SOXL, OrderType.LIMIT, OrderTiming.AT_OPEN, direction, leg,
                new BigDecimal(quantity), new BigDecimal(price), OrderStatus.CANCELLED, externalOrderId, null, null);
    }

    private void withdrawRejected(String amount, Class<? extends RuntimeException> type, String message) {
        assertThatThrownBy(() -> service.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal(amount))))
                .isInstanceOf(type).hasMessage(message);
        verify(cyclePositionPort, never()).updateBalance(any(), any());
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
    }

    @Test
    void withdraw_success_reducesDepositAndStartAmount_keepsHoldingsAndAvg() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        service.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("200.00")));

        AccountBalance p = updatedBalance();
        assertThat(p.usdDeposit()).isEqualByComparingTo("300.00");
        assertThat(p.holdings()).isEqualByComparingTo("10");
        assertThat(p.avgPrice()).isEqualByComparingTo("20.0000");
        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("800.00"));
    }

    @Test
    void withdraw_infinite_exactlyAvailable_succeeds() {
        stub(StrategyType.INFINITE, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        service.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("500.00")));

        assertThat(updatedBalance().usdDeposit()).isEqualByComparingTo("0.00");
        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("500.00"));
    }

    @Test
    void withdraw_rejects_nonPositiveAmount() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        withdrawRejected("0", IllegalArgumentException.class, "투자금 인출 금액은 0보다 커야 합니다.");
        assertThatThrownBy(() -> service.withdraw(strategyId, userId, new WithdrawCapitalCommand(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("투자금 인출 금액은 0보다 커야 합니다.");
    }

    @Test
    void withdraw_rejects_vr() {
        stub(StrategyType.VR, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        withdrawRejected("100", IllegalArgumentException.class, "투자금 인출은 PRIVACY·INFINITE 전략만 지원합니다.");
    }

    @Test
    void withdraw_rejects_endedCycle_beforeLock() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), LocalDate.of(2026, 2, 1));
        withdrawRejected("100", IllegalStateException.class, "진행 중인 사이클이 없어 투자금을 인출할 수 없습니다.");
        verify(strategyCyclePort, never()).lockForUpdate(any());
    }

    @Test
    void withdraw_rejects_cycleEndedWhileWaitingForLock() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        StrategyCycle open = strategyCyclePort.requireLatestByStrategyId(strategyId);
        StrategyCycle ended = new StrategyCycle(cycleId, strategyId, open.strategyVersionId(),
                open.startAmount(), null, open.startDate(), LocalDate.of(2026, 2, 1), null, null);
        when(strategyCyclePort.requireLatestByStrategyId(strategyId)).thenReturn(open, ended);
        withdrawRejected("100", IllegalStateException.class, "진행 중인 사이클이 없어 투자금을 인출할 수 없습니다.");
    }

    @Test
    void withdraw_rejects_overLedgerDeposit() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        withdrawRejected("500.01", IllegalArgumentException.class,
                "투자금 인출 금액이 인출 가능액 $500.00를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소된 매수 주문 금액을 뺀 만큼 인출할 수 있습니다.");
    }

    @Test
    void withdraw_rejects_overAvailableAfterSubtractingTodayAndNextTradeDateBuys_sellIgnored() {
        stub(StrategyType.INFINITE, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        when(orderPort.findPlannedOrPlacedByCycleAndDate(cycleId, TODAY)).thenReturn(List.of(
                order(TODAY, OrderDirection.BUY, "10.00", "10"),    // 100
                order(TODAY, OrderDirection.SELL, "50.00", "100"))); // SELL은 차감 안 함
        when(orderPort.findPlannedOrPlacedByCycleAndDate(cycleId, NEXT)).thenReturn(List.of(
                order(NEXT, OrderDirection.BUY, "25.00", "4")));    // 100

        // 가용 = 500 − 100 − 100 = 300
        withdrawRejected("300.01", IllegalArgumentException.class,
                "투자금 인출 금액이 인출 가능액 $300.00를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소된 매수 주문 금액을 뺀 만큼 인출할 수 있습니다.");
        service.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("300.00")));
        assertThat(updatedBalance().usdDeposit()).isEqualByComparingTo("200.00");
    }

    @Test
    void withdraw_committedBuysExceedLedger_showsZeroAvailable() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        when(orderPort.findPlannedOrPlacedByCycleAndDate(cycleId, TODAY)).thenReturn(List.of());
        when(orderPort.findPlannedOrPlacedByCycleAndDate(cycleId, NEXT)).thenReturn(List.of(
                order(NEXT, OrderDirection.BUY, "100.00", "6"))); // 600 > 장부 500
        withdrawRejected("1", IllegalArgumentException.class,
                "투자금 인출 금액이 인출 가능액 $0.00를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소된 매수 주문 금액을 뺀 만큼 인출할 수 있습니다.");
    }

    @Test
    void withdraw_rejects_whenStartAmountWouldBecomeNonPositive() {
        // 장부 예수금(500)은 충분하지만 startAmount(1000)가 0 이하가 되는 경우를 만들기 위해 startAmount를 작게 둔다
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        StrategyCycle small = new StrategyCycle(cycleId, strategyId, UUID.randomUUID(), new BigDecimal("300.00"), null,
                LocalDate.of(2026, 1, 1), null, null, null);
        when(strategyCyclePort.requireLatestByStrategyId(strategyId)).thenReturn(small);
        withdrawRejected("300.00", IllegalArgumentException.class, "투자금 인출 후 시작 금액이 0 이하가 되어 인출할 수 없습니다.");
    }

    @Test
    void withdraw_locksCycleBeforeSaving() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        service.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("100.00")));

        InOrder order = inOrder(strategyCyclePort, cyclePositionPort);
        order.verify(strategyCyclePort).lockForUpdate(cycleId);
        order.verify(cyclePositionPort).updateBalance(eq(latestId), any());
    }

    @Test
    void withdraw_availableMessage_roundsDown() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        when(orderPort.findPlannedOrPlacedByCycleAndDate(cycleId, TODAY)).thenReturn(List.of());
        when(orderPort.findPlannedOrPlacedByCycleAndDate(cycleId, NEXT)).thenReturn(List.of(
                order(NEXT, OrderDirection.BUY, "0.0033", "1000.5"))); // 3.30165 약정 → 가용 496.69835 (반올림이면 496.70)
        withdrawRejected("496.70", IllegalArgumentException.class,
                "투자금 인출 금액이 인출 가능액 $496.69를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소된 매수 주문 금액을 뺀 만큼 인출할 수 있습니다.");
    }

    // ===== 마감 리포트 창 가드 — FIXED(12:00 KST, 비DST postClose 06:10 이후), 기본은 미보고·오늘 주문 없음(통과) =====
    private static final String WINDOW_ADD = "오늘 마감 리포트가 끝나기 전에는 투자금을 추가할 수 없습니다.";
    private static final String WINDOW_WITHDRAW = "오늘 마감 리포트가 끝나기 전에는 투자금을 인출할 수 없습니다.";

    private void stubTodayOrder(String leg) {
        when(orderPort.findByStrategyId(strategyId, TODAY, TODAY)).thenReturn(List.of(
                order(TODAY, OrderDirection.BUY, "10.00", "1").withLeg(leg)));
    }

    @Test
    void reportWindow_rejectsInjectAndWithdraw_whenAfterPostCloseUnreportedWithStrategyOrder() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        stubTodayOrder("LOC_BUY");

        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(BigDecimal.ONE, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessage(WINDOW_ADD);
        assertThatThrownBy(() -> service.withdraw(strategyId, userId, new WithdrawCapitalCommand(BigDecimal.ONE)))
                .isInstanceOf(IllegalStateException.class).hasMessage(WINDOW_WITHDRAW);
        verify(cyclePositionPort, never()).updateBalance(any(), any());
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
    }

    @Test
    void reportWindow_passes_whenAlreadyReported() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        when(batchRunPort.isReported(TODAY, strategyId)).thenReturn(true);

        service.inject(strategyId, userId, new InjectCapitalCommand(BigDecimal.ONE, null, null));
        verify(cyclePositionPort).updateBalance(eq(latestId), any());
    }

    @Test
    void reportWindow_passes_whenOnlyUnknownLegOrdersToday() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        stubTodayOrder(Order.UNKNOWN_LEG);

        service.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("100.00")));
        verify(cyclePositionPort).updateBalance(eq(latestId), any());
    }

    @Test
    void reportWindow_passes_beforePostClose() {
        // 2026-01-06 04:00 KST(= 2026-01-05T19:00Z) — 비DST postClose 06:10 이전
        service = newService(Clock.fixed(Instant.parse("2026-01-05T19:00:00Z"), FixedClockTestConfig.FIXED.getZone()));
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        service.inject(strategyId, userId, new InjectCapitalCommand(BigDecimal.ONE, null, null));
        verify(cyclePositionPort).updateBalance(eq(latestId), any());
    }

    @Test
    void withdraw_subtractsCancelledPlacedBuysOfUnreportedDates() {
        stub(StrategyType.INFINITE, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        when(orderPort.findCancelledByCycleAndDate(cycleId, TODAY)).thenReturn(List.of(
                cancelled(TODAY, OrderDirection.BUY, "10.00", "10", "E1", "LOC_BUY")));  // 100
        when(orderPort.findCancelledByCycleAndDate(cycleId, NEXT)).thenReturn(List.of(
                cancelled(NEXT, OrderDirection.BUY, "20.00", "5", "E2", "LOC_BUY")));    // 100

        // 가용 = 500 − 100 − 100 = 300
        withdrawRejected("300.01", IllegalArgumentException.class,
                "투자금 인출 금액이 인출 가능액 $300.00를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소된 매수 주문 금액을 뺀 만큼 인출할 수 있습니다.");
    }

    @Test
    void withdraw_ignoresCancelledOfReportedDate() {
        stub(StrategyType.INFINITE, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        when(batchRunPort.isReported(TODAY, strategyId)).thenReturn(true); // 체결분이 이미 장부에 반영됨
        lenient(orderPort.findCancelledByCycleAndDate(cycleId, TODAY)).thenReturn(List.of(
                cancelled(TODAY, OrderDirection.BUY, "10.00", "10", "E1", "LOC_BUY")));

        service.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("500.00")));

        verify(orderPort, never()).findCancelledByCycleAndDate(cycleId, TODAY);
        assertThat(updatedBalance().usdDeposit()).isEqualByComparingTo("0.00");
    }

    @Test
    void withdraw_ignoresCancelledBeforePlacement_sell_unknownLeg() {
        stub(StrategyType.INFINITE, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        when(orderPort.findCancelledByCycleAndDate(cycleId, TODAY)).thenReturn(List.of(
                cancelled(TODAY, OrderDirection.BUY, "10.00", "10", null, "LOC_BUY"),            // 접수 전 취소
                cancelled(TODAY, OrderDirection.SELL, "10.00", "10", "E1", "LOC_SELL"),          // SELL
                cancelled(TODAY, OrderDirection.BUY, "10.00", "10", "E2", Order.UNKNOWN_LEG)));  // 수동 보정 행

        service.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("500.00")));

        assertThat(updatedBalance().usdDeposit()).isEqualByComparingTo("0.00");
    }
}
