package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.domain.model.CancelOutcome;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.InfinitePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.matching.domain.model.VrPosition;
import com.kista.tradingcore.trading.application.event.InsufficientBalanceEvent;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy;
import com.kista.tradingcore.support.StubBrokerApiException;
import org.mockito.InOrder;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.VrCycleOrderStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyCycleSeedType;

// PLANNED → 증권사 접수 → PLACED 마킹 흐름과 가격 보정 호출 조건(currentPrice/position 둘 다 있을 때만) 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("TradingOrderExecutor 단위 테스트")
class TradingOrderExecutorTest {

    @Mock OrderPort orderPort;
    @Mock BrokerOrderCorrectionPort brokerPort;
    @Mock BuyOrderPriceCapper buyOrderPriceCapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock StrategyLedgerBudget ledgerBudget;
    @Mock OrderSubmissionReconciler reconciler;
    @Mock DcaExecutionRecorder dcaRecorder;

    static final LocalDate TODAY = LocalDate.now();

    static final TradingAccount ACCOUNT = TradingAccount.from(new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", null,
            Broker.KIS, null, null));

    static final BrokerAccountRef ACCOUNT_REF = ACCOUNT.brokerRef();

    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();
    static final StrategyCycle CYCLE = new StrategyCycle(STRATEGY_CYCLE_ID, UUID.randomUUID(), null,
            new BigDecimal("1000.00"), null, TODAY, null, null, null);

    static final BigDecimal CURRENT_PRICE = new BigDecimal("50.00");

    static final InfinitePosition POSITION = new InfinitePosition(
            new AccountBalance(0, null, new BigDecimal("20000")), StrategyTicker.SOXL, new BigDecimal("10.00"), 20);

    static final VrPosition VR_POSITION = new VrPosition(
            new AccountBalance(1, new BigDecimal("100.00"), new BigDecimal("5000.00")),
            new BigDecimal("10000.00"), new BigDecimal("15.00"), new BigDecimal("5000.00"), BigDecimal.ZERO, 0);

    // 전략 타입별 상수 — placeOrders 호출 시 캡 분기 결정에 사용
    static final Strategy INFINITE_STRATEGY = new Strategy(UUID.randomUUID(), ACCOUNT.userId(),
            StrategyType.INFINITE, StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    static final Strategy PRIVACY_STRATEGY = new Strategy(UUID.randomUUID(), ACCOUNT.userId(),
            StrategyType.PRIVACY, StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    static final Strategy VR_STRATEGY = new Strategy(UUID.randomUUID(), ACCOUNT.userId(),
            StrategyType.VR, StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);

    // 실제 capability 구현체로 CycleOrderStrategies 조립 — needsCapCheck() 실제 값 검증
    static final CycleOrderStrategies CYCLE_STRATEGIES = new CycleOrderStrategies(List.of(
            new InfiniteCycleOrderStrategy(null, null),
            new PrivacyCycleOrderStrategy(null),
            new VrCycleOrderStrategy(null),
            new DcaCycleOrderStrategy()));

    private TradingOrderExecutor executor() {
        // 실제 OrderSubmitter로 선점→접수→기록 경로까지 끝까지 검증
        return new TradingOrderExecutor(orderPort, buyOrderPriceCapper, CYCLE_STRATEGIES, ledgerBudget,
                new AccountBudgetLock(), new OrderSubmitter(orderPort, brokerPort, eventPublisher), reconciler,
                eventPublisher, dcaRecorder);
    }

    private Order planned(UUID id, OrderDirection direction, String price, int quantity) {
        return new Order(id, ACCOUNT.id(), STRATEGY_CYCLE_ID, TODAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, direction, BigDecimal.valueOf(quantity), new BigDecimal(price), OrderStatus.PLANNED, null, null, null);
    }

    private OrderResult brokerResult(String externalOrderId) {
        return new OrderResult(externalOrderId);
    }

    // 프로덕션 매핑과 동일한 규칙으로 기대 OrderInstruction 구성 — place() stub 매칭용
    private static OrderInstruction instructionOf(Order order) {
        return new OrderInstruction(order.ticker(), order.direction(), order.orderType(), order.quantity().intValueExact(), order.price(),
                order.id().toString());
    }

    @Test
    @DisplayName("BUY 3건 중 2건 잔고 부족이면 InsufficientBalanceEvent가 전략당 정확히 1건(failedCount=2, 일부 접수)")
    void insufficientFundsPublishesOneEventPerCall() {
        Order a = planned(UUID.randomUUID(), OrderDirection.BUY, "50.00", 1);
        Order b = planned(UUID.randomUUID(), OrderDirection.BUY, "49.00", 1);
        Order c = planned(UUID.randomUUID(), OrderDirection.BUY, "48.00", 1);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(a, b, c));
        when(brokerPort.place(eq(instructionOf(a)), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-1"));
        when(brokerPort.place(eq(instructionOf(b)), eq(ACCOUNT_REF))).thenThrow(StubBrokerApiException.insufficientFunds("부족"));
        when(brokerPort.place(eq(instructionOf(c)), eq(ACCOUNT_REF))).thenThrow(StubBrokerApiException.insufficientFunds("부족"));

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, CYCLE, null, POSITION, null, INFINITE_STRATEGY);

        assertThat(result).hasSize(1);
        verify(eventPublisher, times(1)).publishEvent(any(InsufficientBalanceEvent.class));
        verify(eventPublisher).publishEvent(new InsufficientBalanceEvent(ACCOUNT.userId(), ACCOUNT.id(), ACCOUNT.nickname(),
                0, null, StrategyTicker.SOXL, StrategyType.INFINITE, 2, true, OrderDirection.BUY));
        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    @DisplayName("모든 BUY가 잔고 부족이면 SELL이 접수돼도 partiallyPlaced=false")
    void allBuysFailWithSellPlaced_notPartiallyPlaced() {
        Order buy = planned(UUID.randomUUID(), OrderDirection.BUY, "50.00", 1);
        Order sell = planned(UUID.randomUUID(), OrderDirection.SELL, "60.00", 1);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(buy, sell));
        when(brokerPort.place(eq(instructionOf(buy)), eq(ACCOUNT_REF))).thenThrow(StubBrokerApiException.insufficientFunds("부족"));
        when(brokerPort.place(eq(instructionOf(sell)), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-S"));

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, null, POSITION, null, INFINITE_STRATEGY);

        verify(eventPublisher).publishEvent(new InsufficientBalanceEvent(ACCOUNT.userId(), ACCOUNT.id(), ACCOUNT.nickname(),
                0, null, StrategyTicker.SOXL, StrategyType.INFINITE, 1, false, OrderDirection.BUY));
    }

    @Test
    @DisplayName("BUY·SELL이 모두 잔고·수량 부족이면 방향별로 이벤트 1건씩, 일부 접수는 같은 방향 기준")
    void mixedDirectionInsufficient_publishesOneEventPerDirection() {
        Order buy = planned(UUID.randomUUID(), OrderDirection.BUY, "50.00", 1);
        Order sell1 = planned(UUID.randomUUID(), OrderDirection.SELL, "60.00", 1);
        Order sell2 = planned(UUID.randomUUID(), OrderDirection.SELL, "61.00", 1);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(buy, sell1, sell2));
        when(brokerPort.place(eq(instructionOf(buy)), eq(ACCOUNT_REF))).thenThrow(StubBrokerApiException.insufficientFunds("부족"));
        when(brokerPort.place(eq(instructionOf(sell1)), eq(ACCOUNT_REF))).thenThrow(StubBrokerApiException.insufficientFunds("부족"));
        when(brokerPort.place(eq(instructionOf(sell2)), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-S"));

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, null, POSITION, null, INFINITE_STRATEGY);

        verify(eventPublisher).publishEvent(new InsufficientBalanceEvent(ACCOUNT.userId(), ACCOUNT.id(), ACCOUNT.nickname(),
                0, null, StrategyTicker.SOXL, StrategyType.INFINITE, 1, false, OrderDirection.BUY));
        verify(eventPublisher).publishEvent(new InsufficientBalanceEvent(ACCOUNT.userId(), ACCOUNT.id(), ACCOUNT.nickname(),
                0, null, StrategyTicker.SOXL, StrategyType.INFINITE, 1, true, OrderDirection.SELL));
        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    @DisplayName("잔고 부족 알림 처리 중 예외가 나면 관리자 TradingErrorEvent로 알린다")
    void reportFailure_publishesAdminError() {
        Order b = planned(UUID.randomUUID(), OrderDirection.BUY, "50.00", 1);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(b));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenThrow(StubBrokerApiException.insufficientFunds("부족"));
        doThrow(new IllegalStateException("boom")).when(eventPublisher).publishEvent(any(InsufficientBalanceEvent.class));

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, null, POSITION, null, INFINITE_STRATEGY);

        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent t && t.message().contains("잔고 부족 알림 실패")));
    }

    @Test
    @DisplayName("DCA 전략은 InsufficientBalanceEvent 없이 실패 leg마다 INSUFFICIENT_BALANCE 회차 skip을 기록")
    void dcaInsufficientFundsRecordsSkipInsteadOfEvent() {
        Strategy dca = new Strategy(UUID.randomUUID(), ACCOUNT.userId(), StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        Order o = planned(UUID.randomUUID(), OrderDirection.BUY, "50.00", 1).withLeg("DCA_rule-1");
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(o));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenThrow(StubBrokerApiException.insufficientFunds("부족"));

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, null, null, null, dca);

        verify(dcaRecorder).recordOrderRejected(eq(dca), eq(ACCOUNT), eq(TODAY), eq("rule-1"), eq(o),
                eq(DcaSkipReason.INSUFFICIENT_BALANCE), any());
        verify(eventPublisher, never()).publishEvent(any(InsufficientBalanceEvent.class));
    }

    @Test
    @DisplayName("DCA SELL 수량 부족은 INSUFFICIENT_HOLDINGS 회차 skip으로 기록")
    void dcaSellInsufficientRecordsHoldingsSkip() {
        Strategy dca = new Strategy(UUID.randomUUID(), ACCOUNT.userId(), StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        Order o = planned(UUID.randomUUID(), OrderDirection.SELL, "50.00", 1).withLeg("DCA_rule-2");
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(o));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenThrow(StubBrokerApiException.insufficientFunds("부족"));

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, null, null, null, dca);

        verify(dcaRecorder).recordOrderRejected(eq(dca), eq(ACCOUNT), eq(TODAY), eq("rule-2"), eq(o),
                eq(DcaSkipReason.INSUFFICIENT_HOLDINGS), eq("증권사 판매 가능 수량 부족"));
    }

    @Test
    @DisplayName("재캡 중 주문 상태 경합(사용자 취소)이면 재캡만 생략하고 남은 PLANNED를 접수")
    void placeOrders_capConflict_skipsCapAndStillPlaces() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "50.00", 10);
        when(buyOrderPriceCapper.capIfNeeded(any(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new OrderStateConflictException("경합"));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-501"));

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        assertThat(result).hasSize(1);
        verify(orderPort).markPlaced(orderId, "KIS-501");
    }

    @Test
    @DisplayName("currentPrice·position 모두 있으면 매수 가격 보정 후 접수")
    void placeOrders_withPriceAndPosition_capsBeforePlacing() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-001"));

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(buyOrderPriceCapper).capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY.ticker(), null);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(orderId); // DB PK 보존
        assertThat(result.getFirst().status()).isEqualTo(OrderStatus.PLACED);
        assertThat(result.getFirst().externalOrderId()).isEqualTo("KIS-001");
        verify(orderPort).markPlaced(orderId, "KIS-001");
    }

    @Test
    @DisplayName("currentPrice가 없으면 가격 보정 생략 (수동 선행 주문 그대로 접수)")
    void placeOrders_withoutCurrentPrice_skipsCapping() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.SELL, "60.00", 5);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-002"));

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, null, POSITION, null, INFINITE_STRATEGY);

        // currentPrice가 null이면 applyCap이 조기 반환 — capper와 아무 상호작용도 없어야 한다
        verifyNoInteractions(buyOrderPriceCapper);
    }

    @Test
    @DisplayName("PRIVACY + position 없음 → INFINITE 보정 생략 후 PRIVACY 캡 적용")
    void placeOrders_privacyWithoutPosition_appliesPrivacyCap() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.SELL, "60.00", 5);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-003"));

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, null, null, PRIVACY_STRATEGY);

        // PRIVACY 타입으로 위임 — 개별/전체 취소 판단·position/vrPosition 무시는 capIfNeeded 내부(BuyOrderPriceCapperTest)에서 검증
        verify(buyOrderPriceCapper).capIfNeeded(StrategyType.PRIVACY, false, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, null, null, PRIVACY_STRATEGY.ticker(), null);
        verifyNoMoreInteractions(buyOrderPriceCapper);
    }

    @Test
    @DisplayName("VR + vrPosition 없음 → BuyOrderPriceCapper 호출 없이 skip")
    void placeOrders_vrWithoutVrPosition_skipsAllCaps() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "60.00", 1);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-VR-001"));

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, null, null, VR_STRATEGY);

        // applyCap의 vrPosition null 가드가 capIfNeeded(@Transactional) 호출 자체를 막는다 — 빈 트랜잭션도 열리지 않음
        verifyNoInteractions(buyOrderPriceCapper);
    }

    @Test
    @DisplayName("INFINITE + position 없음 → BuyOrderPriceCapper 호출 없이 skip")
    void placeOrders_infiniteWithoutPosition_skipsAllCaps() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "60.00", 1);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-INF-NULL"));

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, null, null, INFINITE_STRATEGY);

        verifyNoInteractions(buyOrderPriceCapper);
    }

    @Test
    @DisplayName("VR + vrPosition 있음 → 접수 전 VR 매수 사다리 가격 보정(capIfNeeded) 호출")
    void placeOrders_vrWithVrPosition_appliesVrCap() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "60.00", 1);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-VR-002"));

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, null, VR_POSITION, VR_STRATEGY);

        // VR 타입 + vrPosition non-null → 접수 전 VR 전용 보정 호출
        verify(buyOrderPriceCapper).capIfNeeded(StrategyType.VR, false, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, null, VR_POSITION, VR_STRATEGY.ticker(), null);
        verifyNoMoreInteractions(buyOrderPriceCapper);
    }

    @Test
    @DisplayName("계획 주문이 없으면 빈 목록 반환 + KIS 접수 호출 없음")
    void placeOrders_noPlannedOrders_returnsEmpty() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        assertThat(result).isEmpty();
        verify(brokerPort, never()).place(any(), any());
        verify(orderPort, never()).markPlaced(any(), any());
    }

    // ─── placeAtOpenOrders (Task 4: AT_OPEN 접수도 동일 BUY cap 정책 적용) ──────────

    @Test
    @DisplayName("VR + vrPosition 있음 → AT_OPEN 접수 전 VR 매수 사다리 가격 보정(capIfNeeded, atOpen=true) 호출")
    void placeAtOpenOrders_vrWithVrPosition_appliesVrCapAtOpenScope() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "60.00", 1);
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-VR-OPEN-001"));

        List<Order> result = executor().placeAtOpenOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, null, VR_POSITION, VR_STRATEGY);

        // AT_OPEN 스코프 전용 보정 — capIfNeeded(atOpen=true)로 위임돼 findAtOpenPlannedByCycleAndDate만 조회한다
        // (atOpen=false 호출은 절대 발생하지 않아야 한다 — 동일 사이클의 AT_CLOSE PLANNED 오염 방지가 이 태스크의 핵심)
        verify(buyOrderPriceCapper).capIfNeeded(StrategyType.VR, true, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, null, VR_POSITION, VR_STRATEGY.ticker(), null);
        verifyNoMoreInteractions(buyOrderPriceCapper);
        verify(orderPort, never()).findPlannedByCycleAndDate(any(), any());
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().externalOrderId()).isEqualTo("KIS-VR-OPEN-001");
    }

    @Test
    @DisplayName("AT_OPEN + currentPrice 없으면 가격 보정 생략")
    void placeAtOpenOrders_withoutCurrentPrice_skipsCapping() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.SELL, "60.00", 5);
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-VR-OPEN-002"));

        executor().placeAtOpenOrders(TODAY, ACCOUNT, CYCLE, null, null, VR_POSITION, VR_STRATEGY);

        verifyNoInteractions(buyOrderPriceCapper);
    }

    @Test
    @DisplayName("INFINITE + position 있음 → AT_OPEN 스코프 보정(capIfNeeded, atOpen=true) 호출")
    void placeAtOpenOrders_infiniteWithPosition_appliesInfiniteCapAtOpenScope() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.SELL, "60.00", 5);
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-INF-OPEN-001"));

        executor().placeAtOpenOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(buyOrderPriceCapper).capIfNeeded(StrategyType.INFINITE, true, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY.ticker(), null);
        verify(orderPort, never()).findPlannedByCycleAndDate(any(), any());
    }

    @Test
    @DisplayName("PRIVACY → AT_OPEN 스코프 보정(capIfNeeded, atOpen=true) 호출")
    void placeAtOpenOrders_privacy_appliesPrivacyCapAtOpenScope() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.SELL, "60.00", 5);
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-PRIV-OPEN-001"));

        executor().placeAtOpenOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, null, null, PRIVACY_STRATEGY);

        verify(buyOrderPriceCapper).capIfNeeded(StrategyType.PRIVACY, true, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, null, null, PRIVACY_STRATEGY.ticker(), null);
        verify(orderPort, never()).findPlannedByCycleAndDate(any(), any());
    }

    @Test
    @DisplayName("AT_OPEN 계획 주문이 없으면 빈 목록 반환 + KIS 접수 호출 없음")
    void placeAtOpenOrders_noPlannedOrders_returnsEmpty() {
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        List<Order> result = executor().placeAtOpenOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        assertThat(result).isEmpty();
        verify(brokerPort, never()).place(any(), any());
        verify(orderPort, never()).markPlaced(any(), any());
    }

    @Test
    @DisplayName("복수 계획 주문을 순서대로 접수하고 각각 PLACED 마킹")
    void placeOrders_multiplePlannedOrders_placesAllInOrder() {
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID();
        Order order1 = planned(id1, OrderDirection.BUY, "50.00", 10);
        Order order2 = planned(id2, OrderDirection.SELL, "60.00", 5);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(order1, order2));
        when(brokerPort.place(eq(instructionOf(order1)), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-101"));
        when(brokerPort.place(eq(instructionOf(order2)), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-102"));

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().id()).isEqualTo(id1);
        assertThat(result.getFirst().externalOrderId()).isEqualTo("KIS-101");
        assertThat(result.get(1).id()).isEqualTo(id2);
        assertThat(result.get(1).externalOrderId()).isEqualTo("KIS-102");
        verify(orderPort).markPlaced(id1, "KIS-101");
        verify(orderPort).markPlaced(id2, "KIS-102");
    }

    @Test
    @DisplayName("markPlaced 1차 실패 시 1회 재시도 후 성공하면 정상 처리")
    void placeOrders_markPlacedFailsOnce_retriesAndSucceeds() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-201"));
        doThrow(new RuntimeException("일시적 DB 오류")).doNothing()
                .when(orderPort).markPlaced(orderId, "KIS-201");

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(orderPort, times(2)).markPlaced(orderId, "KIS-201");
        assertThat(result).hasSize(1); // 재시도 성공 → placed 목록 포함
        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    @DisplayName("접수 사이 DB 주문이 취소·삭제됐으면(상태 경합) 재시도 없이 증권사 주문을 즉시 취소")
    void placeOrders_orderCancelledWhilePlacing_cancelsAtBroker() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-301"));
        doThrow(new OrderStateConflictException("경합")).when(orderPort).markPlaced(orderId, "KIS-301");
        when(brokerPort.cancel(any(), any())).thenReturn(CancelOutcome.CANCELLED);

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(orderPort, times(1)).markPlaced(orderId, "KIS-301"); // 경합은 재시도로 안 풀린다
        verify(brokerPort).cancel(new CancelInstruction(plannedOrder.ticker(), "KIS-301"), ACCOUNT_REF);
        assertThat(result).isEmpty();
        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    @DisplayName("접수 실패 주문이 그 사이 취소·정리됐어도(FAILED 기록 경합) 남은 주문 접수를 계속한다")
    void placeOrders_markFailedConflict_continuesWithRemainingOrders() {
        UUID failedId = UUID.randomUUID();
        UUID okId = UUID.randomUUID();
        Order failing = planned(failedId, OrderDirection.BUY, "50.00", 10);
        Order ok = planned(okId, OrderDirection.SELL, "60.00", 5);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(failing, ok));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF)))
                .thenThrow(StubBrokerApiException.rejected("증권사 거절"))
                .thenReturn(brokerResult("KIS-401"));
        doThrow(new OrderStateConflictException("경합")).when(orderPort).markFailed(failedId);

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        assertThat(result).extracting(Order::id).containsExactly(okId);
        verify(orderPort).markPlaced(okId, "KIS-401");
    }

    @Test
    @DisplayName("상태 경합 후 증권사 취소도 실패하면 수동 취소 알림")
    void placeOrders_orderCancelledWhilePlacing_brokerCancelFails_alerts() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-302"));
        doThrow(new OrderStateConflictException("경합")).when(orderPort).markPlaced(orderId, "KIS-302");
        doThrow(new RuntimeException("증권사 오류")).when(brokerPort).cancel(any(), any());

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.message() != null && tee.message().contains("취소 경합")));
    }

    @Test
    @DisplayName("markPlaced 재시도도 실패하면 DB 불일치 알림 발송")
    void placeOrders_markPlacedFailsTwice_notifiesInconsistency() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-202"));
        doThrow(new RuntimeException("DB down")).when(orderPort).markPlaced(orderId, "KIS-202");

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(orderPort, times(2)).markPlaced(orderId, "KIS-202");
        assertThat(result).isEmpty();
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.message() != null && tee.message().contains("DB 불일치")));
    }

    @Test
    @DisplayName("재캡이 BUY 총액을 늘리면 예산을 사이클 원장 잔여(remaining)로 다시 시도한다")
    void recapPassesCycleLedgerRemaining() {
        when(buyOrderPriceCapper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY.ticker(), null)).thenReturn(true);
        // 원장 1000 − 같은 날 PLANNED BUY(자기 스코프 50×4=200) = 800. capper가 자기 스코프 원본(200)을 더해 1000으로 맞춘다
        when(ledgerBudget.remaining(CYCLE, TODAY)).thenReturn(new BigDecimal("800.00"));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(buyOrderPriceCapper).capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY.ticker(), new BigDecimal("800.00"));
    }

    @Test
    @DisplayName("재캡이 총액을 늘리지 않으면 원장 조회 없음")
    void placeOrders_capWithoutIncrease_noLedgerQuery() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, null, null, PRIVACY_STRATEGY);

        verifyNoInteractions(ledgerBudget);
    }

    @Test
    @DisplayName("접수 전에 잔류 SUBMITTING 대사를 먼저 수행")
    void placeOrders_reconcilesBeforePlacing() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        InOrder inOrder = inOrder(reconciler, orderPort);
        inOrder.verify(reconciler).reconcile(STRATEGY_CYCLE_ID, TODAY, ACCOUNT);
        inOrder.verify(orderPort).findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
    }

    @Test
    @DisplayName("개장 접수도 잔류 SUBMITTING 대사를 먼저 수행")
    void placeAtOpenOrders_reconcilesBeforePlacing() {
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        executor().placeAtOpenOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        InOrder inOrder = inOrder(reconciler, orderPort);
        inOrder.verify(reconciler).reconcile(STRATEGY_CYCLE_ID, TODAY, ACCOUNT);
        inOrder.verify(orderPort).findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
    }

    @Test
    @DisplayName("루프 도중 취소로 지워진 주문(선점 경합)에는 HTTP를 보내지 않는다")
    void placeEach_orderDeletedMidLoop_notSent() {
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID();
        Order order1 = planned(id1, OrderDirection.BUY, "50.00", 10);
        Order order2 = planned(id2, OrderDirection.SELL, "60.00", 5);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(order1, order2));
        when(brokerPort.place(eq(instructionOf(order1)), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-601"));
        lenient().doThrow(new OrderStateConflictException("삭제됨")).when(orderPort).markSubmitting(id2);

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        assertThat(result).extracting(Order::id).containsExactly(id1);
        verify(brokerPort, times(1)).place(any(OrderInstruction.class), any());
    }

    @Test
    @DisplayName("결과 불명 접수는 FAILED로 쓰지 않고 다음 주문을 계속 접수")
    void placeEach_ambiguousFailure_keepsSubmittingAndContinues() {
        UUID unknownId = UUID.randomUUID(), okId = UUID.randomUUID();
        Order unknown = planned(unknownId, OrderDirection.BUY, "50.00", 10);
        Order ok = planned(okId, OrderDirection.SELL, "60.00", 5);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(unknown, ok));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF)))
                .thenThrow(new StubBrokerApiException("read timed out"))
                .thenReturn(brokerResult("KIS-701"));

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, CYCLE, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        assertThat(result).extracting(Order::id).containsExactly(okId);
        verify(orderPort, never()).markFailed(unknownId);
        verify(orderPort).markPlaced(okId, "KIS-701");
    }
}
