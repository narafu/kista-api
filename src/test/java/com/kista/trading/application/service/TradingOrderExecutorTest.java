package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.trading.domain.model.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.trading.domain.model.VrPosition;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.domain.model.OrderType;
import com.kista.trading.domain.strategy.CycleOrderStrategies;
import com.kista.trading.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.trading.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.trading.domain.strategy.VrCycleOrderStrategy;
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
            Account.Broker.KIS, null);

    static final BrokerAccountRef ACCOUNT_REF = toBrokerRef(ACCOUNT);

    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();

    static final BigDecimal CURRENT_PRICE = new BigDecimal("50.00");

    static final InfinitePosition POSITION = new InfinitePosition(
            new AccountBalance(0, null, new BigDecimal("20000")), Ticker.SOXL, new BigDecimal("10.00"), 20);

    static final VrPosition VR_POSITION = new VrPosition(
            new AccountBalance(1, new BigDecimal("100.00"), new BigDecimal("5000.00")),
            new BigDecimal("10000.00"), new BigDecimal("15.00"), new BigDecimal("5000.00"), BigDecimal.ZERO, 0);

    // 전략 타입별 상수 — placeOrders 호출 시 캡 분기 결정에 사용
    static final Strategy INFINITE_STRATEGY = new Strategy(UUID.randomUUID(), ACCOUNT.userId(),
            Strategy.Type.INFINITE, Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
    static final Strategy PRIVACY_STRATEGY = new Strategy(UUID.randomUUID(), ACCOUNT.userId(),
            Strategy.Type.PRIVACY, Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
    static final Strategy VR_STRATEGY = new Strategy(UUID.randomUUID(), ACCOUNT.userId(),
            Strategy.Type.VR, Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);

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

    private Order planned(UUID id, Order.OrderDirection direction, String price, int quantity) {
        return new Order(id, ACCOUNT.id(), STRATEGY_CYCLE_ID, TODAY, Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, direction, quantity, new BigDecimal(price), Order.OrderStatus.PLANNED, null, null, null);
    }

    private OrderResult brokerResult(String externalOrderId) {
        return new OrderResult(externalOrderId);
    }

    // 프로덕션 매핑과 동일한 규칙으로 기대 OrderInstruction 구성 — place() stub 매칭용
    private static OrderInstruction instructionOf(Order order) {
        Direction direction = order.direction() == Order.OrderDirection.BUY ? Direction.BUY : Direction.SELL;
        OrderType orderType = switch (order.orderType()) {
            case LOC -> OrderType.LOC;
            case MOC -> OrderType.MOC;
            case LIMIT -> OrderType.LIMIT;
        };
        return new OrderInstruction(order.ticker(), direction, orderType, order.quantity(), order.price());
    }

    @Test
    @DisplayName("currentPrice·position 모두 있으면 매수 가격 보정 후 접수")
    void placeOrders_withPriceAndPosition_capsBeforePlacing() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-001"));

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(buyOrderPriceCapper).capIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(orderId); // DB PK 보존
        assertThat(result.getFirst().status()).isEqualTo(Order.OrderStatus.PLACED);
        assertThat(result.getFirst().externalOrderId()).isEqualTo("KIS-001");
        verify(orderPort).markPlaced(orderId, "KIS-001");
    }

    @Test
    @DisplayName("currentPrice가 없으면 가격 보정 생략 (수동 선행 주문 그대로 접수)")
    void placeOrders_withoutCurrentPrice_skipsCapping() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.SELL, "60.00", 5);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-002"));

        executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, null, POSITION, null, INFINITE_STRATEGY);

        verify(buyOrderPriceCapper, never()).capIfNeeded(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PRIVACY + position 없음 → INFINITE 보정 생략 후 PRIVACY 캡 적용")
    void placeOrders_privacyWithoutPosition_appliesPrivacyCap() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.SELL, "60.00", 5);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-003"));

        executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null, null, PRIVACY_STRATEGY);

        // INFINITE 보정(capIfNeeded)은 호출되지 않음
        verify(buyOrderPriceCapper, never()).capIfNeeded(any(), any(), any(), any(), any());
        // PRIVACY 캡(capPrivacyIfNeeded)은 PRIVACY 전략일 때 호출됨
        verify(buyOrderPriceCapper).capPrivacyIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE);
    }

    @Test
    @DisplayName("VR + vrPosition 없음 → post-hoc 캡 미적용 (재계산 skip 케이스)")
    void placeOrders_vrWithoutVrPosition_skipsAllCaps() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.BUY, "60.00", 1);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-VR-001"));

        executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null, null, VR_STRATEGY);

        // vrPosition이 null(재계산 skip 케이스)이면 INFINITE_POSITION과 동일한 원칙으로 post-hoc 캡을 건너뛴다
        verify(buyOrderPriceCapper, never()).capIfNeeded(any(), any(), any(), any(), any());
        verify(buyOrderPriceCapper, never()).capPrivacyIfNeeded(any(), any(), any(), any());
        verify(buyOrderPriceCapper, never()).capVrIfNeeded(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("VR + vrPosition 있음 → 접수 전 VR 매수 사다리 가격 보정(capVrIfNeeded) 호출")
    void placeOrders_vrWithVrPosition_appliesVrCap() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.BUY, "60.00", 1);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-VR-002"));

        executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null, VR_POSITION, VR_STRATEGY);

        // VR_POSITION mode + vrPosition non-null → 접수 전 VR 전용 보정 호출 (INFINITE_POSITION은 미호출)
        verify(buyOrderPriceCapper).capVrIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, VR_POSITION, VR_STRATEGY.ticker());
        verify(buyOrderPriceCapper, never()).capIfNeeded(any(), any(), any(), any(), any());
        verify(buyOrderPriceCapper, never()).capPrivacyIfNeeded(any(), any(), any(), any());
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
    @DisplayName("VR + vrPosition 있음 → AT_OPEN 접수 전 VR 매수 사다리 가격 보정(capVrIfNeededAtOpen) 호출")
    void placeAtOpenOrders_vrWithVrPosition_appliesVrCapAtOpenScope() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.BUY, "60.00", 1);
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-VR-OPEN-001"));

        List<Order> result = executor().placeAtOpenOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null, VR_POSITION, VR_STRATEGY);

        // AT_OPEN 스코프 전용 보정(capVrIfNeededAtOpen) 호출 — 접수 전 자리에서 findPlannedByCycleAndDate(전체 PLANNED)를
        // 쓰는 capVrIfNeeded는 절대 호출되지 않아야 한다 (동일 사이클의 AT_CLOSE PLANNED 오염 방지가 이 태스크의 핵심)
        verify(buyOrderPriceCapper).capVrIfNeededAtOpen(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, VR_POSITION, VR_STRATEGY.ticker());
        verify(buyOrderPriceCapper, never()).capVrIfNeeded(any(), any(), any(), any(), any(), any());
        verify(orderPort, never()).findPlannedByCycleAndDate(any(), any());
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().externalOrderId()).isEqualTo("KIS-VR-OPEN-001");
    }

    @Test
    @DisplayName("AT_OPEN + currentPrice 없으면 가격 보정 생략")
    void placeAtOpenOrders_withoutCurrentPrice_skipsCapping() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.SELL, "60.00", 5);
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-VR-OPEN-002"));

        executor().placeAtOpenOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, null, null, VR_POSITION, VR_STRATEGY);

        verify(buyOrderPriceCapper, never()).capVrIfNeededAtOpen(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("INFINITE + position 있음 → AT_OPEN 스코프 보정(capIfNeededAtOpen) 호출")
    void placeAtOpenOrders_infiniteWithPosition_appliesInfiniteCapAtOpenScope() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.SELL, "60.00", 5);
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-INF-OPEN-001"));

        executor().placeAtOpenOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(buyOrderPriceCapper).capIfNeededAtOpen(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION);
        verify(orderPort, never()).findPlannedByCycleAndDate(any(), any());
    }

    @Test
    @DisplayName("PRIVACY → AT_OPEN 스코프 보정(capPrivacyIfNeededAtOpen) 호출")
    void placeAtOpenOrders_privacy_appliesPrivacyCapAtOpenScope() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.SELL, "60.00", 5);
        when(orderPort.findAtOpenPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-PRIV-OPEN-001"));

        executor().placeAtOpenOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null, null, PRIVACY_STRATEGY);

        verify(buyOrderPriceCapper).capPrivacyIfNeededAtOpen(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE);
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
        Order order1 = planned(id1, Order.OrderDirection.BUY, "50.00", 10);
        Order order2 = planned(id2, Order.OrderDirection.SELL, "60.00", 5);
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
        Order plannedOrder = planned(orderId, Order.OrderDirection.BUY, "50.00", 10);
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
        Order plannedOrder = planned(orderId, Order.OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT_REF))).thenReturn(brokerResult("KIS-202"));
        doThrow(new RuntimeException("DB down")).when(orderPort).markPlaced(orderId, "KIS-202");

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, null, INFINITE_STRATEGY);

        verify(orderPort, times(2)).markPlaced(orderId, "KIS-202");
        assertThat(result).isEmpty();
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.message() != null && tee.message().contains("DB 불일치")));
    }

    // broker 모듈 순환 방지 — Account → BrokerAccountRef 변환 (broker는 Account를 직접 참조하지 않음)
    private static BrokerAccountRef toBrokerRef(Account account) {
        return new BrokerAccountRef(
                account.id(), account.appKey(), account.secretKey(),
                account.accountNo(), account.brokerAccountCode(),
                BrokerAccountRef.Broker.valueOf(account.broker().name()));
    }
}
