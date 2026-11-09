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
import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.trading.application.port.output.CycleCapitalEventPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.trading.application.port.output.StrategyInfiniteDetailPort;
import com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.tradingcore.trading.domain.model.StrategyInfiniteDetail;
import com.kista.tradingcore.trading.application.event.StrategyCapitalChangedEvent;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CapitalLedgerService 단위 테스트 (추가·인출)")
class CapitalLedgerServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock AccountPort accountPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock OrderPort orderPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TradingBatchRunPort batchRunPort;
    @Mock CycleOrderStrategies cycleOrderStrategies;
    @Mock CycleCapitalEventPort cycleCapitalEventPort;
    @Mock BrokerPricePort brokerPricePort;
    @Mock StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    final CycleOrderStrategies minSeedStrategies = new CycleOrderStrategies(List.of(new InfiniteCycleOrderStrategy(null, null)));

    CapitalLedgerService service;
    CapitalWithdrawalService withdrawal; // 인출 진입점(전일종가 선조회 후 service.withdraw 호출)

    private final UUID strategyId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID cycleId = UUID.randomUUID();
    private final UUID latestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = newService(FixedClockTestConfig.FIXED);
        withdrawal = new CapitalWithdrawalService(strategyPort, accountPort,
                new InfiniteMinSeedGuard(brokerPricePort, minSeedStrategies, strategyInfiniteDetailPort), service);
        stubAccount(TradingFixtures.kisAccount(accountId, userId)); // 기본: 주식(USD) 계좌
    }

    // 소유권 검증 default 메서드 자체를 stub — 반환 계좌의 통화·시장이 금액 자릿수·리포트 창 검사를 정한다
    private void stubAccount(Account account) {
        org.mockito.Mockito.lenient().when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(account);
    }

    private CapitalLedgerService newService(Clock clock) {
        return new CapitalLedgerService(strategyPort, accountPort, strategyCyclePort, cyclePositionPort, orderPort, batchRunPort,
                new StrategyLedgerBudget(cyclePositionPort, orderPort, batchRunPort, cycleOrderStrategies), clock, eventPublisher, cycleCapitalEventPort);
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
        assertThat(p.deposit()).isEqualByComparingTo("800.00");
        assertThat(p.holdings()).isEqualByComparingTo("10");
        assertThat(p.avgPrice()).isEqualByComparingTo("20.0000");
        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("1300.00"));
        verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(userId));
    }

    private CycleCapitalEvent savedEvent() {
        ArgumentCaptor<CycleCapitalEvent> c = ArgumentCaptor.forClass(CycleCapitalEvent.class);
        verify(cycleCapitalEventPort).save(c.capture());
        return c.getValue();
    }

    private void assertEvent(CycleCapitalEvent e, CycleCapitalEvent.Kind kind, String deposit, String holdings, String principal) {
        assertThat(e.id()).isNull();
        assertThat(e.strategyCycleId()).isEqualTo(cycleId);
        assertThat(e.cyclePositionId()).isEqualTo(latestId);
        assertThat(e.kind()).isEqualTo(kind);
        assertThat(e.depositDelta()).isEqualByComparingTo(deposit);
        assertThat(e.holdingsDelta()).isEqualByComparingTo(holdings);
        assertThat(e.principalDelta()).isEqualByComparingTo(principal);
        assertThat(e.occurredAt()).isEqualTo(Instant.now(FixedClockTestConfig.FIXED));
    }

    @Test
    void inject_rejects_archived_strategy() {
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(
                new Strategy(strategyId, accountId, StrategyType.PRIVACY, StrategyStatus.ARCHIVED, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));

        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("500.00"), null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("보관된 전략은 변경할 수 없습니다.");

        verify(strategyCyclePort, never()).lockForUpdate(any());
        verify(cyclePositionPort, never()).updateBalance(any(), any());
    }

    @Test
    void inject_depositAndShares_recordsEvent() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("100.0000"), null);

        service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("500.00"), 5, new BigDecimal("120.00")));

        assertEvent(savedEvent(), CycleCapitalEvent.Kind.INJECT, "500", "5", "1100"); // 500 + 5×120
    }

    @Test
    void inject_depositOnly_recordsZeroHoldingsDelta() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("100.0000"), null);

        service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("300.00"), null, null));

        assertEvent(savedEvent(), CycleCapitalEvent.Kind.INJECT, "300", "0", "300");
    }

    @Test
    void withdraw_recordsNegativeDeltas() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("100.0000"), null);

        withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("300.00")));

        assertEvent(savedEvent(), CycleCapitalEvent.Kind.WITHDRAW, "-300", "0", "-300");
    }

    @Test
    void sharesOnly_weightedAverage() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        service.inject(strategyId, userId, new InjectCapitalCommand(null, 10, new BigDecimal("30.00")));

        AccountBalance p = updatedBalance();
        assertThat(p.holdings()).isEqualByComparingTo("20");
        assertThat(p.avgPrice()).isEqualByComparingTo("25.0000"); // (20×10 + 30×10) / 20
        assertThat(p.deposit()).isEqualByComparingTo("500.00");
        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("1300.00"));
    }

    @Test
    void sharesOnly_avgRoundsDirectlyToScale2() {
        stub(StrategyType.PRIVACY, BigDecimal.valueOf(199), new BigDecimal("45.12"), null);

        service.inject(strategyId, userId, new InjectCapitalCommand(null, 1, new BigDecimal("46.11")));

        // (199×45.12 + 46.11) / 200 = 45.12495 → 4자리 선반올림 45.1250 경유 금지
        assertThat(updatedBalance().avgPrice()).isEqualTo(new BigDecimal("45.12"));
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
        assertThat(p.deposit()).isEqualByComparingTo("600.00");
        assertThat(p.holdings()).isEqualByComparingTo("15");
        assertThat(p.avgPrice()).isEqualByComparingTo("26.67"); // (200 + 200) / 15 = 26.666… → scale 2 HALF_UP
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
    void rejects_vr() {
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(
                new Strategy(strategyId, accountId, StrategyType.VR, StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE));
        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(BigDecimal.ONE, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("투자금 추가는 PRIVACY·INFINITE·DCA 전략만 지원합니다.");
        verify(cyclePositionPort, never()).save(any());
    }

    @Test
    void dca_depositOnly_addsDepositAndStartAmount() {
        stub(StrategyType.DCA, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("300.00"), null, null));

        assertThat(updatedBalance().deposit()).isEqualByComparingTo("800.00");
        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("1300.00"));
    }

    @Test
    void dca_rejects_shares() {
        stub(StrategyType.DCA, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(null, 5, new BigDecimal("30.00"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("DCA 전략은 예수금만 추가할 수 있습니다.");
        verify(cyclePositionPort, never()).updateBalance(any(), any());
    }

    @Test
    void dca_crypto_inject_skipsReportWindow() {
        // 코인 계좌는 마감 리포트가 없다 — 당일 주문 조회(리포트 창 판정) 자체를 하지 않는다
        stubAccount(TradingFixtures.upbitAccount(accountId, userId));
        stub(StrategyType.DCA, BigDecimal.ZERO, null, null);

        service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("100000"), null, null));

        assertThat(updatedBalance().deposit()).isEqualByComparingTo("100500.00");
        verify(orderPort, never()).findByStrategyId(any(), any(), any());
    }

    @Test
    void dca_crypto_withdraw_overAvailable_usesCurrencyFormat() {
        stubAccount(TradingFixtures.upbitAccount(accountId, userId));
        stub(StrategyType.DCA, BigDecimal.ZERO, null, null);

        // 가용 = 장부 예수금 500 → 501 요청은 "500원" 표기로 거절
        withdrawRejected("501", IllegalArgumentException.class,
                "투자금 인출 금액이 인출 가능액 %s를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소 전에 체결된 매수 금액을 뺀 만큼 인출할 수 있습니다."
                        .formatted(Currency.KRW.format(new BigDecimal("500"))));
        verify(orderPort, never()).findByStrategyId(any(), any(), any()); // 리포트 창 검사 생략
    }

    @Test
    void dca_crypto_withdraw_subtractsOpenOrdersOfCycleOnly_noCancelledHold() {
        stubAccount(TradingFixtures.upbitAccount(accountId, userId));
        stub(StrategyType.DCA, BigDecimal.ZERO, null, null);
        // 날짜 무관 미체결 BUY 200(같은 사이클) + 다른 사이클 BUY 300(무시) → 가용 500 − 200 = 300
        Order sameCycle = order(TODAY.minusDays(1), OrderDirection.BUY, "100", "2");
        Order otherCycle = new Order(null, accountId, UUID.randomUUID(), TODAY, StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, new BigDecimal("3"), new BigDecimal("100"), OrderStatus.PLACED, null, null, null);
        when(orderPort.findOpenByAccountId(accountId)).thenReturn(List.of(sameCycle, otherCycle));

        withdrawRejected("301", IllegalArgumentException.class,
                "투자금 인출 금액이 인출 가능액 %s를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소 전에 체결된 매수 금액을 뺀 만큼 인출할 수 있습니다."
                        .formatted(Currency.KRW.format(new BigDecimal("300"))));
        verify(orderPort, never()).findCancelledByCycleAndDate(any(), any()); // 코인은 취소분 보류 체결 없음
    }

    @Test
    void dca_crypto_rejects_amountBeyondCurrencyScale() {
        stubAccount(TradingFixtures.upbitAccount(accountId, userId));
        stub(StrategyType.DCA, BigDecimal.ZERO, null, null);

        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(new BigDecimal("100.5"), null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("금액은 정수로 입력해야 합니다.");
    }

    @Test
    void rejects_endedCycle() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), LocalDate.of(2026, 2, 1));
        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(BigDecimal.ONE, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("진행 중인 사이클이 없어 투자금을 추가할 수 없습니다.");
        verify(strategyCyclePort, never()).lockForUpdate(any());
    }

    // ===== 투자금 인출 — 기본 stub: deposit 500.00, startAmount 1000.00, FIXED 시계(오늘 2026-01-06, 다음 거래일 2026-01-07) =====
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

    private Order cancelledFilled(LocalDate date, String price, String quantity, String filledQuantity, String filledPrice) {
        return new Order(null, accountId, cycleId, date, StrategyTicker.SOXL, OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.BUY, "LOC_BUY",
                new BigDecimal(quantity), new BigDecimal(price), OrderStatus.CANCELLED, "E1", new BigDecimal(filledQuantity),
                filledPrice == null ? null : new BigDecimal(filledPrice));
    }

    private void withdrawRejected(String amount, Class<? extends RuntimeException> type, String message) {
        assertThatThrownBy(() -> withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal(amount))))
                .isInstanceOf(type).hasMessage(message);
        verify(cyclePositionPort, never()).updateBalance(any(), any());
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
    }

    @Test
    void withdraw_success_reducesDepositAndStartAmount_keepsHoldingsAndAvg() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("200.00")));

        AccountBalance p = updatedBalance();
        assertThat(p.deposit()).isEqualByComparingTo("300.00");
        assertThat(p.holdings()).isEqualByComparingTo("10");
        assertThat(p.avgPrice()).isEqualByComparingTo("20.0000");
        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("800.00"));
        verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(userId));
    }

    @Test
    void withdraw_infinite_exactlyAvailable_succeeds() {
        stub(StrategyType.INFINITE, BigDecimal.TEN, new BigDecimal("20.0000"), null);

        withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("500.00")));

        assertThat(updatedBalance().deposit()).isEqualByComparingTo("0.00");
        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("500.00"));
    }

    // 인출 후 INFINITE 최소 시드(CQ-63): 전일종가 60, 분할 20 → 최소 2,400. 시작금액 3,000에서 인출
    private void stubInfiniteWithdrawal() {
        stub(StrategyType.INFINITE, BigDecimal.ZERO, null, null);
        when(strategyCyclePort.requireLatestByStrategyId(strategyId)).thenReturn(new StrategyCycle(cycleId, strategyId,
                UUID.randomUUID(), new BigDecimal("3000.00"), null, LocalDate.of(2026, 1, 1), null, null, null));
        when(cyclePositionPort.findLatestOne(cycleId)).thenReturn(Optional.of(new CyclePosition(latestId, cycleId,
                new BigDecimal("3000.00"), new BigDecimal("30.00"), null, BigDecimal.ZERO, null, null)));
        when(strategyInfiniteDetailPort.findActiveByStrategyId(strategyId))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(UUID.randomUUID(), 20)));
    }

    @Test
    void withdraw_infinite_belowMinSeed_rejected() {
        stubInfiniteWithdrawal();
        when(brokerPricePort.getPrevClose(eq(StrategyTicker.SOXL), any())).thenReturn(new BigDecimal("60"));

        withdrawRejected("600.01", IllegalArgumentException.class, "최소 투자금 $2,400.00 이상이 필요합니다.");
    }

    @Test
    void withdraw_infinite_atMinSeed_succeeds() {
        stubInfiniteWithdrawal();
        when(brokerPricePort.getPrevClose(eq(StrategyTicker.SOXL), any())).thenReturn(new BigDecimal("60"));

        withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("600.00")));

        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("2400.00"));
    }

    @Test
    void withdraw_infinite_priceFetchFails_skipsMinCheck() {
        stubInfiniteWithdrawal();
        when(brokerPricePort.getPrevClose(eq(StrategyTicker.SOXL), any())).thenThrow(new RuntimeException("시세 장애"));

        withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("1000.00")));

        verify(strategyCyclePort).updateStartAmount(cycleId, new BigDecimal("2000.00"));
    }

    @Test
    void withdraw_infinite_invalidAmount_rejectedBeforePriceFetch() {
        stub(StrategyType.INFINITE, BigDecimal.ZERO, null, null);

        assertThatThrownBy(() -> withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("1.234"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("금액은 소수 2자리까지 입력할 수 있습니다.");
        verifyNoInteractions(brokerPricePort); // 싼 검증이 외부 시세 호출보다 먼저
    }

    @Test
    void withdraw_rejects_nonPositiveAmount() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        withdrawRejected("0", IllegalArgumentException.class, "투자금 인출 금액은 0보다 커야 합니다.");
        assertThatThrownBy(() -> withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("투자금 인출 금액은 0보다 커야 합니다.");
    }

    @Test
    void withdraw_rejects_vr() {
        stub(StrategyType.VR, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        withdrawRejected("100", IllegalArgumentException.class, "투자금 인출은 PRIVACY·INFINITE·DCA 전략만 지원합니다.");
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
                "투자금 인출 금액이 인출 가능액 $500.00를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소 전에 체결된 매수 금액을 뺀 만큼 인출할 수 있습니다.");
        verify(cycleCapitalEventPort, never()).save(any());
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
                "투자금 인출 금액이 인출 가능액 $300.00를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소 전에 체결된 매수 금액을 뺀 만큼 인출할 수 있습니다.");
        withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("300.00")));
        assertThat(updatedBalance().deposit()).isEqualByComparingTo("200.00");
    }

    @Test
    void withdraw_committedBuysExceedLedger_showsZeroAvailable() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        when(orderPort.findPlannedOrPlacedByCycleAndDate(cycleId, TODAY)).thenReturn(List.of());
        when(orderPort.findPlannedOrPlacedByCycleAndDate(cycleId, NEXT)).thenReturn(List.of(
                order(NEXT, OrderDirection.BUY, "100.00", "6"))); // 600 > 장부 500
        withdrawRejected("1", IllegalArgumentException.class,
                "투자금 인출 금액이 인출 가능액 $0.00를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소 전에 체결된 매수 금액을 뺀 만큼 인출할 수 있습니다.");
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

        withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("100.00")));

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
                "투자금 인출 금액이 인출 가능액 $496.69를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소 전에 체결된 매수 금액을 뺀 만큼 인출할 수 있습니다.");
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
        assertThatThrownBy(() -> withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(BigDecimal.ONE)))
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

        withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("100.00")));
        verify(cyclePositionPort).updateBalance(eq(latestId), any());
    }

    private void stubPausedStrategy() {
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(
                new Strategy(strategyId, accountId, StrategyType.PRIVACY, StrategyStatus.PAUSED, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
    }

    @Test
    void reportWindow_passes_whenPausedAndOrdersNeverPlaced() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        stubPausedStrategy();
        stubTodayOrder("LOC_BUY"); // 접수 안 된 PLANNED 주문 — 정지 전략은 마감 리포트 대상 아님

        service.inject(strategyId, userId, new InjectCapitalCommand(BigDecimal.ONE, null, null));
        verify(cyclePositionPort).updateBalance(eq(latestId), any());
    }

    @Test
    void reportWindow_rejects_whenPausedWithPlacedOrder() {
        stub(StrategyType.PRIVACY, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        stubPausedStrategy();
        when(orderPort.findByStrategyId(strategyId, TODAY, TODAY)).thenReturn(List.of(
                order(TODAY, OrderDirection.BUY, "10.00", "1").withLeg("LOC_BUY").withPlaced("EXT-1")));

        assertThatThrownBy(() -> service.inject(strategyId, userId, new InjectCapitalCommand(BigDecimal.ONE, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessage(WINDOW_ADD);
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
                "투자금 인출 금액이 인출 가능액 $300.00를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소 전에 체결된 매수 금액을 뺀 만큼 인출할 수 있습니다.");
    }

    @Test
    void withdraw_ignoresCancelledOfReportedDate() {
        stub(StrategyType.INFINITE, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        when(batchRunPort.isReported(TODAY, strategyId)).thenReturn(true); // 체결분이 이미 장부에 반영됨
        lenient(orderPort.findCancelledByCycleAndDate(cycleId, TODAY)).thenReturn(List.of(
                cancelled(TODAY, OrderDirection.BUY, "10.00", "10", "E1", "LOC_BUY")));

        withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("500.00")));

        verify(orderPort, never()).findCancelledByCycleAndDate(cycleId, TODAY);
        assertThat(updatedBalance().deposit()).isEqualByComparingTo("0.00");
    }

    @Test
    void withdraw_ignoresCancelledBeforePlacement_sell_unknownLeg() {
        stub(StrategyType.INFINITE, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        when(orderPort.findCancelledByCycleAndDate(cycleId, TODAY)).thenReturn(List.of(
                cancelled(TODAY, OrderDirection.BUY, "10.00", "10", null, "LOC_BUY"),            // 접수 전 취소
                cancelled(TODAY, OrderDirection.SELL, "10.00", "10", "E1", "LOC_SELL"),          // SELL
                cancelled(TODAY, OrderDirection.BUY, "10.00", "10", "E2", Order.UNKNOWN_LEG)));  // 수동 보정 행

        withdrawal.withdraw(strategyId, userId, new WithdrawCapitalCommand(new BigDecimal("500.00")));

        assertThat(updatedBalance().deposit()).isEqualByComparingTo("0.00");
    }

    @Test
    void withdraw_subtractsOnlyRecordedFillOfCancelledBuy() {
        stub(StrategyType.INFINITE, BigDecimal.TEN, new BigDecimal("20.0000"), null);
        when(orderPort.findCancelledByCycleAndDate(cycleId, TODAY)).thenReturn(List.of(
                cancelledFilled(TODAY, "10.00", "10", "3", "9.50"),   // 체결 28.50
                cancelledFilled(TODAY, "10.00", "10", "0", null)));   // 미체결 0

        // 가용 = 500 − 28.50 = 471.50 (주문 금액 전체 100 × 2가 아님)
        withdrawRejected("471.51", IllegalArgumentException.class,
                "투자금 인출 금액이 인출 가능액 $471.50를 초과합니다. 장부 예수금에서 체결 전 매수 주문 금액과 장중 취소 전에 체결된 매수 금액을 뺀 만큼 인출할 수 있습니다.");
    }
}
