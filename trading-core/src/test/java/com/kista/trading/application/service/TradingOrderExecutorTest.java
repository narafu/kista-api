package com.kista.trading.application.service;

import com.kista.sharedkernel.OrderStatus;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.trading.domain.model.Order;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.matching.domain.model.AccountBalance;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.trading.domain.model.Strategy;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.matching.domain.model.VrPosition;
import com.kista.sharedkernel.TradingErrorEvent;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.matching.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.matching.domain.strategy.VrCycleOrderStrategy;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.*;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyCycleSeedType;

// PLANNED → 증권사 접수 → PLACED 마킹 흐름과 가격 보정 호출 조건(currentPrice/position 둘 다 있을 때만) 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("TradingOrderExecutor 단위 테스트")
class TradingOrderExecutorTest {

    @Mock OrderPort orderPort;
    @Mock BrokerAdapterRegistry registry;
    @Mock BrokerOrderCorrectionPort brokerPort;  // registry.require(account, BrokerOrderCorrectionPort.class) 반환값
    @Mock BuyOrderPriceCapper buyOrderPriceCapper;
    @Mock ApplicationEventPublisher eventPublisher;

    static final LocalDate TODAY = LocalDate.now();

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", null,
            Broker.KIS, null);

    static final BrokerAccountRef ACCOUNT_REF = ACCOUNT.toBrokerRef();

    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();

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

    // 실제 capability 구현체로 CycleOrderStrategies 조립 — priceCapMode() 실제 값 검증
    static final CycleOrderStrategies CYCLE_STRATEGIES = new CycleOrderStrategies(List.of(
            new InfiniteCycleOrderStrategy(null, null),
            new PrivacyCycleOrderStrategy(null),
            new VrCycleOrderStrategy(null)));

    @BeforeEach
    void setUp() {
        // registry.require(account, BrokerOrderCorrectionPort.class) → brokerPort 반환 스텁 (일부 테스트는 도달 전 종료 → lenient)
        lenient().doReturn(brokerPort).when(registry).require(any(BrokerAccountRef.class), any());
    }

    private TradingOrderExecutor executor() {
        return new TradingOrderExecutor(orderPort, registry, buyOrderPriceCapper, eventPublisher, CYCLE_STRATEGIES);
    }

    private Order planned(UUID id, OrderDirection direction, String price, int quantity) {
        return new Order(id, ACCOUNT.id(), STRATEGY_CYCLE_ID, TODAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, direction, quantity, new BigDecimal(price), OrderStatus.PLANNED, null, null, null);
    }

    private OrderResult brokerResult(String externalOrderId) {
        return new OrderResult(externalOrderId);
    }

    // 프로덕션 매핑과 동일한 규칙으로 기대 OrderInstruction 구성 — place() stub 매칭용
    private static OrderInstruction instructionOf(Order order) {
        return new OrderInstruction(order.ticker(), order.direction(), order.orderType(), order.quantity(), order.price());
    }

    @Test
    @DisplayName("currentPrice·position 모두 있으면 매수 가격 보정 후 접수")
    void placeOrders_withPriceAndPosition_capsBeforePlacing() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-001"));

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(buyOrderPriceCapper).capIfNeeded(CycleOrderStrategy.PriceCapMode.INFINITE_POSITION, false, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY.ticker());
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

        executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, null, POSITION, null, INFINITE_STRATEGY);

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

        executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null, null, PRIVACY_STRATEGY);

        // PRIVACY_SIMPLE 모드로 위임 — mode 판단·position/vrPosition 무시는 capIfNeeded 내부(BuyOrderPriceCapperTest)에서 검증
        verify(buyOrderPriceCapper).capIfNeeded(CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE, false, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, null, null, PRIVACY_STRATEGY.ticker());
        verifyNoMoreInteractions(buyOrderPriceCapper);
    }

    @Test
    @DisplayName("VR + vrPosition 없음 → BuyOrderPriceCapper 호출 없이 skip")
    void placeOrders_vrWithoutVrPosition_skipsAllCaps() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "60.00", 1);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-VR-001"));

        executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null, null, VR_STRATEGY);

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

        executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null, null, INFINITE_STRATEGY);

        verifyNoInteractions(buyOrderPriceCapper);
    }

    @Test
    @DisplayName("VR + vrPosition 있음 → 접수 전 VR 매수 사다리 가격 보정(capIfNeeded) 호출")
    void placeOrders_vrWithVrPosition_appliesVrCap() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "60.00", 1);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-VR-002"));

        executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null, VR_POSITION, VR_STRATEGY);

        // VR_POSITION mode + vrPosition non-null → 접수 전 VR 전용 보정 호출
        verify(buyOrderPriceCapper).capIfNeeded(CycleOrderStrategy.PriceCapMode.VR_POSITION, false, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, null, VR_POSITION, VR_STRATEGY.ticker());
        verifyNoMoreInteractions(buyOrderPriceCapper);
    }

    @Test
    @DisplayName("계획 주문이 없으면 빈 목록 반환 + KIS 접수 호출 없음")
    void placeOrders_noPlannedOrders_returnsEmpty() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

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

        List<Order> result = executor().placeAtOpenOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null, VR_POSITION, VR_STRATEGY);

        // AT_OPEN 스코프 전용 보정 — capIfNeeded(atOpen=true)로 위임돼 findAtOpenPlannedByCycleAndDate만 조회한다
        // (atOpen=false 호출은 절대 발생하지 않아야 한다 — 동일 사이클의 AT_CLOSE PLANNED 오염 방지가 이 태스크의 핵심)
        verify(buyOrderPriceCapper).capIfNeeded(CycleOrderStrategy.PriceCapMode.VR_POSITION, true, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, null, VR_POSITION, VR_STRATEGY.ticker());
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

        executor().placeAtOpenOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, null, null, VR_POSITION, VR_STRATEGY);

        verifyNoInteractions(buyOrderPriceCapper);
    }

    @Test
    @DisplayName("INFINITE + position 있음 → AT_OPEN 스코프 보정(capIfNeeded, atOpen=true) 호출")
    void placeAtOpenOrders_infiniteWithPosition_appliesInfiniteCapAtOpenScope() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.SELL, "60.00", 5);
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-INF-OPEN-001"));

        executor().placeAtOpenOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(buyOrderPriceCapper).capIfNeeded(CycleOrderStrategy.PriceCapMode.INFINITE_POSITION, true, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY.ticker());
        verify(orderPort, never()).findPlannedByCycleAndDate(any(), any());
    }

    @Test
    @DisplayName("PRIVACY → AT_OPEN 스코프 보정(capIfNeeded, atOpen=true) 호출")
    void placeAtOpenOrders_privacy_appliesPrivacyCapAtOpenScope() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.SELL, "60.00", 5);
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-PRIV-OPEN-001"));

        executor().placeAtOpenOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null, null, PRIVACY_STRATEGY);

        verify(buyOrderPriceCapper).capIfNeeded(CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE, true, TODAY, ACCOUNT,
                STRATEGY_CYCLE_ID, CURRENT_PRICE, null, null, PRIVACY_STRATEGY.ticker());
        verify(orderPort, never()).findPlannedByCycleAndDate(any(), any());
    }

    @Test
    @DisplayName("AT_OPEN 계획 주문이 없으면 빈 목록 반환 + KIS 접수 호출 없음")
    void placeAtOpenOrders_noPlannedOrders_returnsEmpty() {
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        List<Order> result = executor().placeAtOpenOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

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

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(id1);
        assertThat(result.get(0).externalOrderId()).isEqualTo("KIS-101");
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

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(orderPort, times(2)).markPlaced(orderId, "KIS-201");
        assertThat(result).hasSize(1); // 재시도 성공 → placed 목록 포함
        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    @DisplayName("markPlaced 재시도도 실패하면 DB 불일치 알림 발송")
    void placeOrders_markPlacedFailsTwice_notifiesInconsistency() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-202"));
        doThrow(new RuntimeException("DB down")).when(orderPort).markPlaced(orderId, "KIS-202");

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(orderPort, times(2)).markPlaced(orderId, "KIS-202");
        assertThat(result).isEmpty();
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.message() != null && tee.message().contains("DB 불일치")));
    }
}

