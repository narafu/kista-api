package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.trading.domain.model.ReorderCommand;
import com.kista.tradingcore.trading.domain.model.ReorderResult;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;
import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
class ReorderServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock OrderPort orderPort;
    @Mock BrokerOrderCorrectionPort brokerOrderCorrectionPort;
    @Mock MarketCalendarPort marketCalendarPort;
    @Mock OrderSubmitter orderSubmitter;

    ReorderService service;

    @BeforeEach
    void setUp() {
        // stateWriter는 실제 인스턴스에 mock orderPort를 위임 — 기존 verify(orderPort).markCancelled 검증 유지
        service = new ReorderService(accountPort, strategyPort, strategyCyclePort, orderPort,
                brokerOrderCorrectionPort, marketCalendarPort, new OrderCancelStateWriter(orderPort), orderSubmitter);
    }

    private static final UUID USER_ID    = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID STRATEGY_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID CYCLE_ID   = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID ORDER_ID   = UUID.fromString("00000000-0000-0000-0000-000000000050");

    // 시장 단계별 테스트 상수 (DST=true 기준, 미국 EDT)
    // marketOpen = 22:30 KST = 13:30 UTC
    // marketClose = 05:00 KST = 20:00 UTC (전날)
    // BLOCKED = [05:00, 17:00) KST = [20:00 UTC D-1, 08:00 UTC D)

    // 개장 전 (DIRECT 시간대, 19:00 KST = 10:00 UTC — before 22:30 open)
    private static final DstInfo DST_FOR_TEST = new DstInfo(true,
            Instant.parse("2026-07-02T19:30:00Z"),  // orderAt (무관)
            Instant.parse("2026-07-02T20:10:00Z"),  // postClose (무관)
            Instant.parse("2026-07-01T13:30:00Z")); // marketOpen = 22:30 KST July 1
    private static final Instant NOW_BEFORE_OPEN = Instant.parse("2026-07-01T10:00:00Z"); // 19:00 KST July 1

    // 정규장 중 (DIRECT 시간대, 02:00 KST July 2 = 17:00 UTC July 1 — after 22:30 open)
    private static final Instant NOW_DURING_MARKET = Instant.parse("2026-07-01T17:00:00Z"); // 02:00 KST July 2

    // 장 마감 후 (BLOCKED, 07:00 KST July 2 = 22:00 UTC July 1 — between close 05:00 and premarket 17:00 KST)
    private static final Instant NOW_AFTER_CLOSE = Instant.parse("2026-07-01T22:00:00Z"); // 07:00 KST July 2

    // --- 상태별 취소 + PLANNED 저장 ---

    @Test
    void reorder_fromPlanned_cancelsThenSavesPlanned() {
        stubCommon(plannedOrder());

        ReorderResult result = reorder(command(OrderTiming.AT_CLOSE), NOW_BEFORE_OPEN);

        verify(orderPort).cancelPlanned(ORDER_ID); // PLANNED 원본은 조건부 전이
        verify(orderPort, never()).markCancelled(any());
        verify(orderPort).saveAll(argOrdersMatch(OrderStatus.PLANNED, OrderTiming.AT_CLOSE));
        assertThat(result.originalStatus()).isEqualTo(OrderStatus.PLANNED);
        assertThat(result.resultingStatus()).isEqualTo(OrderStatus.PLANNED);
    }

    @Test
    void reorder_fromPlaced_cancelsBrokerThenSavesPlanned() {
        stubCommon(placedOrder());

        reorder(command(OrderTiming.AT_CLOSE), NOW_BEFORE_OPEN);

        verify(brokerOrderCorrectionPort).cancel(new CancelInstruction(placedOrder().ticker(), placedOrder().externalOrderId()), account().toBrokerRef()); // 증권사 취소
        verify(orderPort).markCancelled(ORDER_ID);
        verify(orderPort).saveAll(argOrdersMatch(OrderStatus.PLANNED, OrderTiming.AT_CLOSE));
    }

    @Test
    void reorder_fromPlanned_whenBatchPlacedMeanwhile_abortsWithoutNewOrder() {
        // 읽은 뒤 배치가 원본을 접수(PLACED)함 — 새 주문을 얹으면 중복이므로 중단
        stubCommon(plannedOrder());
        doThrow(new OrderStateConflictException("경합")).when(orderPort).cancelPlanned(ORDER_ID);

        assertThatThrownBy(() -> reorder(command(OrderTiming.AT_CLOSE), NOW_BEFORE_OPEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("상태가 바뀌었습니다");

        verify(orderPort, never()).saveAll(any());
        verify(brokerOrderCorrectionPort, never()).place(any(), any());
    }

    @Test
    void reorder_tradeDateOtherThanCurrent_rejectedBeforeCancellingSource() {
        // 19:00 KST July 1 → 현재 거래일 July 2 — 지난 거래일(July 1)로는 배치가 접수하지 않는 죽은 주문이 된다
        stubCommon(plannedOrder());
        ReorderCommand stale = new ReorderCommand(USER_ID, ACCOUNT_ID, STRATEGY_ID, ORDER_ID, OrderTiming.AT_CLOSE,
                LocalDate.of(2026, 7, 1), null, 2, new BigDecimal("250.00"), "memo");

        assertThatThrownBy(() -> service.reorder(stale, DST_FOR_TEST, NOW_BEFORE_OPEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("현재 거래일");

        verify(orderPort, never()).cancelPlanned(any());
        verify(orderPort, never()).saveAll(any());
    }

    @Test
    void reorder_checksMarketCalendarWithTradeDateOfDecisionTime() {
        // 19:00 KST July 1 → 거래일 July 2(04:30 경계) — 오늘 KST 일자(July 1)를 넘기면 직전 세션을 검사하게 된다
        stubCommon(plannedOrder());

        reorder(command(OrderTiming.AT_CLOSE), NOW_BEFORE_OPEN);

        verify(marketCalendarPort).isMarketOpen(LocalDate.of(2026, 7, 2));
    }

    @Test
    void reorder_fromFilled_rejected() {
        // 체결된 원본 위에 새 주문을 얹으면 같은 주문이 중복으로 나간다 — 체결분 보정은 수동 체결 보정 API
        stubSelection(filledOrder());

        assertThatThrownBy(() -> reorder(command(OrderTiming.AT_OPEN), NOW_BEFORE_OPEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("미체결");

        verify(orderPort, never()).markCancelled(any());
        verify(orderPort, never()).saveAll(any());
    }

    @Test
    void reorder_fromFailed_rejected() {
        stubSelection(failedOrder());

        assertThatThrownBy(() -> reorder(command(OrderTiming.AT_OPEN), NOW_BEFORE_OPEN))
                .isInstanceOf(IllegalArgumentException.class);

        verify(orderPort, never()).saveAll(any());
    }

    // --- IMMEDIATE 접수 ---

    // 저장 후 id가 채워진 PLANNED 재주문 — 선점 전 id 확보(멱등키) 흐름 검증용
    private Order stubSaved() {
        Order saved = new Order(UUID.randomUUID(), ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 2), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.IMMEDIATE, OrderDirection.SELL, BigDecimal.valueOf(1),
                new BigDecimal("236.54"), OrderStatus.PLANNED, null, null, null);
        when(orderPort.save(any())).thenReturn(saved);
        return saved;
    }

    @Test
    void immediate_savesBeforeSubmitting() {
        stubCommon(plannedOrder());
        Order saved = stubSaved();
        when(orderSubmitter.submit(eq(saved), any(), any()))
                .thenReturn(new OrderSubmitter.Submission(OrderSubmitter.Outcome.PLACED, "NEW-EXT-1"));

        reorder(command(OrderTiming.IMMEDIATE), NOW_DURING_MARKET);

        InOrder inOrder = inOrder(orderPort, orderSubmitter);
        inOrder.verify(orderPort).save(argThat(o -> o.status() == OrderStatus.PLANNED && o.timing() == OrderTiming.IMMEDIATE));
        inOrder.verify(orderSubmitter).submit(eq(saved), eq(account().toBrokerRef()), any());
        verify(brokerOrderCorrectionPort, never()).place(any(), any());
    }

    @Test
    void immediate_placed_returnsPlacedWithExternalId() {
        stubCommon(plannedOrder());
        Order saved = stubSaved();
        when(orderSubmitter.submit(eq(saved), any(), any()))
                .thenReturn(new OrderSubmitter.Submission(OrderSubmitter.Outcome.PLACED, "NEW-EXT-1"));

        ReorderResult result = reorder(command(OrderTiming.IMMEDIATE), NOW_DURING_MARKET);

        assertThat(result.resultingStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(result.newOrderExternalId()).isEqualTo("NEW-EXT-1");
    }

    @Test
    void immediate_rejected_returnsFailed() {
        stubCommon(plannedOrder());
        Order saved = stubSaved();
        when(orderSubmitter.submit(eq(saved), any(), any()))
                .thenReturn(new OrderSubmitter.Submission(OrderSubmitter.Outcome.REJECTED, null));

        assertThat(reorder(command(OrderTiming.IMMEDIATE), NOW_DURING_MARKET).resultingStatus())
                .isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void immediate_unknown_returnsSubmitting() {
        // 결과 불명 — 재시도가 중복 주문을 내지 않도록 SUBMITTING으로 응답(런북 정정 대상)
        stubCommon(plannedOrder());
        Order saved = stubSaved();
        when(orderSubmitter.submit(eq(saved), any(), any()))
                .thenReturn(new OrderSubmitter.Submission(OrderSubmitter.Outcome.UNKNOWN, null));

        assertThat(reorder(command(OrderTiming.IMMEDIATE), NOW_DURING_MARKET).resultingStatus())
                .isEqualTo(OrderStatus.SUBMITTING);
    }

    @Test
    void immediate_skipped_returnsCurrentDbStatus() {
        // 저장 직후 배치가 먼저 선점·접수함 — 현재 DB 상태를 그대로 응답
        stubCommon(plannedOrder());
        Order saved = stubSaved();
        when(orderSubmitter.submit(eq(saved), any(), any()))
                .thenReturn(new OrderSubmitter.Submission(OrderSubmitter.Outcome.SKIPPED, null));
        when(orderPort.findById(saved.id())).thenReturn(Optional.of(saved.withPlaced("BATCH-EXT")));

        ReorderResult result = reorder(command(OrderTiming.IMMEDIATE), NOW_DURING_MARKET);

        assertThat(result.resultingStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(result.newOrderExternalId()).isEqualTo("BATCH-EXT");
    }

    @Test
    void immediate_saveFails_noBrokerCall() {
        stubCommon(plannedOrder());
        when(orderPort.save(any())).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> reorder(command(OrderTiming.IMMEDIATE), NOW_DURING_MARKET))
                .isInstanceOf(RuntimeException.class).hasMessage("DB down");
        verifyNoInteractions(orderSubmitter);
        verify(brokerOrderCorrectionPort, never()).place(any(), any());
    }

    // --- 시점 가용성 검증 ---

    @Test
    void reorder_immediateWhenClosed_throwsIllegalArgument() {
        stubCommon(plannedOrder());

        assertThatThrownBy(() -> reorder(command(OrderTiming.IMMEDIATE), NOW_AFTER_CLOSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IMMEDIATE");
    }

    @Test
    void reorder_atOpenAfterMarketOpen_throwsIllegalArgument() {
        stubCommon(plannedOrder());

        // NOW_DURING_MARKET = 정규장 중 → AT_OPEN 불가 (개장 이후)
        assertThatThrownBy(() -> reorder(command(OrderTiming.AT_OPEN), NOW_DURING_MARKET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AT_OPEN");
    }

    @Test
    void reorder_allTimingsWhenClosed_allThrow() {
        stubCommon(plannedOrder());

        for (OrderTiming timing : OrderTiming.values()) {
            assertThatThrownBy(() -> reorder(command(timing), NOW_AFTER_CLOSE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- 휴장일 가드 ---

    @Test
    void reorder_onMarketHoliday_throwsIllegalArgument() {
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy());
        when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(cycle());
        when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(plannedOrder()));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(false);

        assertThatThrownBy(() -> reorder(command(OrderTiming.AT_CLOSE), NOW_BEFORE_OPEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("휴장일");
    }

    @Test
    void reorder_onMarketHoliday_doesNotCancelPlacedSourceAtBroker() {
        // 검증이 증권사 취소보다 먼저 — 휴장일 거절 시 원본 PLACED 주문이 증권사에서 취소된 채 남지 않아야 한다
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy());
        when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(cycle());
        when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(placedOrder()));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(false);

        assertThatThrownBy(() -> reorder(command(OrderTiming.AT_CLOSE), NOW_BEFORE_OPEN))
                .isInstanceOf(IllegalArgumentException.class);

        verify(brokerOrderCorrectionPort, never()).cancel(any(), any());
        verify(orderPort, never()).markCancelled(any());
    }

    // --- 헬퍼 ---

    private ReorderResult reorder(ReorderCommand command, Instant now) {
        return service.reorder(command, DST_FOR_TEST, now);
    }

    private void stubCommon(Order order) {
        stubSelection(order);
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
    }

    // 계좌·전략·사이클·원본 주문 선택까지만 — 원본 상태 검증에서 거절되는 경로용
    private void stubSelection(Order order) {
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy());
        when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(cycle());
        when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Order> argOrdersMatch(OrderStatus status, OrderTiming timing) {
        return org.mockito.ArgumentMatchers.argThat(orders ->
                ((java.util.List<Order>) orders).size() == 1
                && ((java.util.List<Order>) orders).getFirst().status() == status
                && ((java.util.List<Order>) orders).getFirst().timing() == timing);
    }

    private Account account() {
        return new Account(ACCOUNT_ID, USER_ID, "Toss", "1234-56", "app", "secret", "seq-1",
                Broker.TOSS, null);
    }

    private Strategy strategy() {
        return new Strategy(STRATEGY_ID, ACCOUNT_ID, StrategyType.PRIVACY, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.MAX);
    }

    private StrategyCycle cycle() {
        return new StrategyCycle(CYCLE_ID, STRATEGY_ID, UUID.randomUUID(), new BigDecimal("6989.00"),
                null, LocalDate.of(2026, 6, 21), null, Instant.now(), null);
    }

    private Order plannedOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 2), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL, BigDecimal.valueOf(1),
                new BigDecimal("236.54"), OrderStatus.PLANNED, null, null, null);
    }

    private Order placedOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 2), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL, BigDecimal.valueOf(1),
                new BigDecimal("236.54"), OrderStatus.PLACED, "PLACED-1", null, null);
    }

    private Order filledOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 2), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL, BigDecimal.valueOf(2),
                new BigDecimal("236.54"), OrderStatus.FILLED, "FILLED-1", BigDecimal.valueOf(2), new BigDecimal("236.54"));
    }

    private Order failedOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 2), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL, BigDecimal.valueOf(1),
                new BigDecimal("236.54"), OrderStatus.FAILED, null, null, null);
    }

    private ReorderCommand command(OrderTiming timing) {
        return new ReorderCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID, ORDER_ID,
                timing,
                LocalDate.of(2026, 7, 2),
                null,
                2,
                new BigDecimal("250.00"),
                "reorder memo"
        );
    }

    @Test
    void immediate_claimFailedLeavingPlanned_cancelsSoBatchDoesNotPlaceLater() {
        // 선점이 경합 아닌 오류로 실패해 PLANNED(IMMEDIATE)가 남으면 마감 배치가 나중에 접수한다 — 정리하고 CANCELLED로 응답
        stubCommon(plannedOrder());
        Order saved = stubSaved();
        when(orderSubmitter.submit(eq(saved), any(), any()))
                .thenReturn(new OrderSubmitter.Submission(OrderSubmitter.Outcome.SKIPPED, null));
        when(orderPort.findById(saved.id())).thenReturn(Optional.of(saved));

        ReorderResult result = reorder(command(OrderTiming.IMMEDIATE), NOW_DURING_MARKET);

        verify(orderPort).cancelPlanned(saved.id());
        assertThat(result.resultingStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void immediate_claimFailed_cancelAlsoFails_reportsPlannedWithoutThrowing() {
        // 정리 취소까지 DB 오류면 예외 대신 사실대로 PLANNED 응답(원본은 이미 취소됨 — 예외로 끊으면 응답이 사라진다)
        stubCommon(plannedOrder());
        Order saved = stubSaved();
        when(orderSubmitter.submit(eq(saved), any(), any()))
                .thenReturn(new OrderSubmitter.Submission(OrderSubmitter.Outcome.SKIPPED, null));
        when(orderPort.findById(saved.id())).thenReturn(Optional.of(saved));
        org.mockito.Mockito.lenient().doThrow(new RuntimeException("DB down")).when(orderPort).cancelPlanned(saved.id()); // 원본 취소(ORDER_ID)와 인자가 달라 lenient

        assertThat(reorder(command(OrderTiming.IMMEDIATE), NOW_DURING_MARKET).resultingStatus())
                .isEqualTo(OrderStatus.PLANNED);
    }
}

