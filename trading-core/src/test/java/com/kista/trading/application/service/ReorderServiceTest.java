package com.kista.trading.application.service;

import com.kista.sharedkernel.OrderStatus;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.trading.domain.model.Order;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.trading.domain.model.DstInfo;
import com.kista.trading.domain.model.ReorderCommand;
import com.kista.trading.domain.model.ReorderResult;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.account.application.port.output.AccountPort;
import com.kista.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.application.port.output.BrokerOrderCorrectionPort;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
class ReorderServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock OrderPort orderPort;
    @Mock BrokerOrderCorrectionPort brokerOrderCorrectionPort;
    @Mock MarketCalendarPort marketCalendarPort;

    ReorderService service;

    @BeforeEach
    void setUp() {
        // stateWriter는 실제 인스턴스에 mock orderPort를 위임 — 기존 verify(orderPort).markCancelled 검증 유지
        service = new ReorderService(accountPort, strategyPort, strategyCyclePort, orderPort,
                brokerOrderCorrectionPort, marketCalendarPort, new OrderCancelStateWriter(orderPort));
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

        verify(orderPort).markCancelled(ORDER_ID);
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

    @Test
    void reorder_immediate_success_savesPlaced() {
        stubCommon(plannedOrder());
        when(brokerOrderCorrectionPort.place(any(), any())).thenReturn(new OrderResult("NEW-EXT-1"));

        ReorderResult result = reorder(command(OrderTiming.IMMEDIATE), NOW_DURING_MARKET);

        verify(orderPort).saveAll(argOrdersMatch(OrderStatus.PLACED, OrderTiming.IMMEDIATE));
        assertThat(result.resultingStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(result.newOrderExternalId()).isEqualTo("NEW-EXT-1");
    }

    @Test
    void reorder_immediate_brokerError_savesFailed() {
        stubCommon(plannedOrder());
        when(brokerOrderCorrectionPort.place(any(), any())).thenThrow(new RuntimeException("증권사 오류"));

        ReorderResult result = reorder(command(OrderTiming.IMMEDIATE), NOW_DURING_MARKET);

        verify(orderPort).saveAll(argOrdersMatch(OrderStatus.FAILED, OrderTiming.IMMEDIATE));
        assertThat(result.resultingStatus()).isEqualTo(OrderStatus.FAILED);
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
                && ((java.util.List<Order>) orders).get(0).status() == status
                && ((java.util.List<Order>) orders).get(0).timing() == timing);
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
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 1), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL, 1,
                new BigDecimal("236.54"), OrderStatus.PLANNED, null, null, null);
    }

    private Order placedOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 1), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL, 1,
                new BigDecimal("236.54"), OrderStatus.PLACED, "PLACED-1", null, null);
    }

    private Order filledOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 1), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL, 2,
                new BigDecimal("236.54"), OrderStatus.FILLED, "FILLED-1", 2, new BigDecimal("236.54"));
    }

    private Order failedOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 1), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL, 1,
                new BigDecimal("236.54"), OrderStatus.FAILED, null, null, null);
    }

    private ReorderCommand command(OrderTiming timing) {
        return new ReorderCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID, ORDER_ID,
                timing,
                LocalDate.of(2026, 7, 1),
                null,
                2,
                new BigDecimal("250.00"),
                "reorder memo"
        );
    }
}

