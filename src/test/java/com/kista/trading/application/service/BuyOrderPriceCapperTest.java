package com.kista.trading.application.service;

import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.trading.domain.model.Order;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.matching.domain.model.OrderType;
import com.kista.matching.domain.model.OrderTiming;
import com.kista.matching.domain.model.OrderDirection;
import com.kista.matching.domain.model.AccountBalance;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.matching.domain.model.VrPosition;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.matching.domain.strategy.InfiniteStrategy;
import com.kista.matching.domain.strategy.VrStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

// BUY PLANNED 가격이 currentPrice × 1.05 초과 시 전략별 재산정 위임 후 영속화 — I/O 오케스트레이션만 검증
// 캡 가격 재산정 공식(병합/보정 등)은 InfiniteStrategyTypeTest.buildCappedBuyOrders / VrStrategyTypeTest.buildCappedBuyOrders 참고
// PRIVACY: position 없이 단순 가격 캡만 적용 (capPrivacyIfNeeded)
@ExtendWith(MockitoExtension.class)
class BuyOrderPriceCapperTest {

    @Mock OrderPort orderPort;
    @Mock TradingOrderPlanner orderPlanner;
    @Mock InfiniteStrategy infiniteStrategy;
    @Mock VrStrategy vrStrategy;
    @Mock StrategyCyclePort strategyCyclePort;
    @Captor ArgumentCaptor<List<PlannedOrder>> ordersCaptor;
    @Captor ArgumentCaptor<BigDecimal> capCaptor;

    static final LocalDate TODAY = LocalDate.now();

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", null,
            Broker.KIS, null);

    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();

    static final InfinitePosition POSITION = new InfinitePosition(
            new AccountBalance(0, null, new BigDecimal("20000")), StrategyTicker.SOXL, new BigDecimal("10.00"), 20);

    static final VrPosition VR_POSITION = new VrPosition(
            new AccountBalance(1, new BigDecimal("100.00"), new BigDecimal("5000.00")),
            new BigDecimal("10000.00"), new BigDecimal("15.00"), new BigDecimal("5000.00"), BigDecimal.ZERO, 0);

    private BuyOrderPriceCapper capper() {
        return new BuyOrderPriceCapper(orderPort, orderPlanner, infiniteStrategy, vrStrategy, strategyCyclePort);
    }

    // 영속 Order 헬퍼 — orderPort.findPlannedByCycleAndDate 스텁·capIfNeeded류(DB I/O) 테스트 전용
    private Order buy(String price, int quantity) {
        return new Order(null, null, null, TODAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, OrderDirection.BUY, quantity, new BigDecimal(price), Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order buy(String price, int quantity, String orderLeg) {
        return Order.fromPlanned(PlannedOrder.of(TODAY, StrategyTicker.SOXL, OrderType.LOC, OrderDirection.BUY,
                quantity, new BigDecimal(price), orderLeg), null, null);
    }

    private Order sell(String price, int quantity) {
        return new Order(null, null, null, TODAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, OrderDirection.SELL, quantity, new BigDecimal(price), Order.OrderStatus.PLANNED, null, null, null);
    }

    // VR 사다리 BUY/SELL은 항상 LIMIT+AT_OPEN(ticker=TQQQ) — bootstrap(LOC+AT_CLOSE)과 구분하기 위해 별도 헬퍼로 생성
    private Order vrLadderBuy(String price, int quantity) {
        return new Order(null, null, null, TODAY, StrategyTicker.TQQQ, OrderType.LIMIT,
                OrderTiming.AT_OPEN, OrderDirection.BUY, quantity, new BigDecimal(price), Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order vrLadderSell(String price, int quantity) {
        return new Order(null, null, null, TODAY, StrategyTicker.TQQQ, OrderType.LIMIT,
                OrderTiming.AT_OPEN, OrderDirection.SELL, quantity, new BigDecimal(price), Order.OrderStatus.PLANNED, null, null, null);
    }

    @Test
    void prepareForAllocation_infiniteCap_returnsCappedBuysAndCorrectionsWithoutPersistence() {
        PlannedOrder originalBuy = buy("60.00", 1, "INFINITE_LATE_REF_BUY").toPlanned();
        PlannedOrder cappedBuy = buy("52.50", 9, "INFINITE_LATE_REF_BUY").toPlanned();
        PlannedOrder correction = buy("50.00", 1, "INFINITE_CORRECTION_01").toPlanned();
        when(infiniteStrategy.buildCappedBuyOrders(
                POSITION, TODAY, List.of(originalBuy), new BigDecimal("52.50")))
                .thenReturn(List.of(cappedBuy, correction));

        List<PlannedOrder> prepared = capper().prepareForAllocation(
                List.of(originalBuy), new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL,
                CycleOrderStrategy.PriceCapMode.INFINITE_POSITION, TODAY);

        assertThat(prepared).containsExactly(cappedBuy, correction);
        assertThat(prepared).extracting(PlannedOrder::orderLeg)
                .containsExactly("INFINITE_LATE_REF_BUY", "INFINITE_CORRECTION_01");
        verify(infiniteStrategy).buildCappedBuyOrders(
                POSITION, TODAY, List.of(originalBuy), new BigDecimal("52.50"));
        verifyNoInteractions(orderPort, orderPlanner, vrStrategy);
    }

    @Test
    void prepareForAllocation_privacyCap_changesOnlyExceedingBuyPrices() {
        PlannedOrder exceedingBuy = buy("40.00", 5).toPlanned();
        PlannedOrder sell = sell("45.00", 2).toPlanned();
        PlannedOrder withinCapBuy = buy("28.00", 3).toPlanned();

        List<PlannedOrder> prepared = capper().prepareForAllocation(
                List.of(exceedingBuy, sell, withinCapBuy), new BigDecimal("30.00"), null, null, StrategyTicker.SOXL,
                CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE, TODAY);

        assertThat(prepared.get(0).price()).isEqualByComparingTo("31.50");
        assertThat(prepared.get(0).quantity()).isEqualTo(5);
        assertThat(prepared.get(1)).isSameAs(sell);
        assertThat(prepared.get(2)).isSameAs(withinCapBuy);
        verifyNoInteractions(orderPort, orderPlanner, infiniteStrategy, vrStrategy);
    }

    @Test
    void prepareForAllocation_preservesSellOrdersAndOriginalRelativeOrder() {
        PlannedOrder firstBuy = buy("60.00", 1).toPlanned();
        PlannedOrder firstSell = sell("70.00", 1).toPlanned();
        PlannedOrder secondBuy = buy("52.00", 1).toPlanned();
        PlannedOrder secondSell = sell("75.00", 2).toPlanned();
        PlannedOrder firstCappedBuy = buy("52.50", 9).toPlanned();
        PlannedOrder secondCappedBuy = buy("52.00", 9).toPlanned();
        PlannedOrder correction = buy("50.00", 1).toPlanned();
        when(infiniteStrategy.buildCappedBuyOrders(
                POSITION, TODAY, List.of(firstBuy, secondBuy), new BigDecimal("52.50")))
                .thenReturn(List.of(firstCappedBuy, secondCappedBuy, correction));

        List<PlannedOrder> prepared = capper().prepareForAllocation(
                List.of(firstBuy, firstSell, secondBuy, secondSell), new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL,
                CycleOrderStrategy.PriceCapMode.INFINITE_POSITION, TODAY);

        assertThat(prepared).containsExactly(
                firstCappedBuy, firstSell, secondCappedBuy, secondSell, correction);
        verifyNoInteractions(orderPort, orderPlanner, vrStrategy);
    }

    @Test
    void prepareForAllocation_noCapReturnsOriginalOrders() {
        List<PlannedOrder> orders = List.of(buy("60.00", 1).toPlanned(), sell("70.00", 1).toPlanned());

        List<PlannedOrder> prepared = capper().prepareForAllocation(
                orders, new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL,
                CycleOrderStrategy.PriceCapMode.NONE, TODAY);

        assertThat(prepared).isSameAs(orders);
        verifyNoInteractions(orderPort, orderPlanner, infiniteStrategy, vrStrategy);
    }

    @Test
    void prepareForAllocation_nullModeReturnsOriginalOrders() {
        List<PlannedOrder> orders = List.of(buy("60.00", 1).toPlanned(), sell("70.00", 1).toPlanned());

        List<PlannedOrder> prepared = capper().prepareForAllocation(
                orders, new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL, null, TODAY);

        assertThat(prepared).isSameAs(orders);
        verifyNoInteractions(orderPort, orderPlanner, infiniteStrategy, vrStrategy);
    }

    // ─── VR 캡 (prepareForAllocation VR_POSITION mode) ──────────────────────────

    @Test
    void prepareForAllocation_vrCap_returnsCappedBuysWithoutPersistence() {
        // VR 사다리 BUY/SELL은 LIMIT+AT_OPEN — bootstrap(LOC+AT_CLOSE)과 다른 형태라야 VR_POSITION 보정 대상이 된다
        PlannedOrder originalBuy = vrLadderBuy("8500.00", 1).toPlanned();
        PlannedOrder sell = vrLadderSell("11500.00", 1).toPlanned();
        PlannedOrder cappedBuy = vrLadderBuy("525.00", 2).toPlanned();
        when(vrStrategy.buildCappedBuyOrders(VR_POSITION, StrategyTicker.TQQQ, TODAY, new BigDecimal("52.50")))
                .thenReturn(List.of(cappedBuy));

        List<PlannedOrder> prepared = capper().prepareForAllocation(
                List.of(originalBuy, sell), new BigDecimal("50.00"), null, VR_POSITION, StrategyTicker.TQQQ,
                CycleOrderStrategy.PriceCapMode.VR_POSITION, TODAY);

        assertThat(prepared).containsExactly(cappedBuy, sell);
        verify(vrStrategy).buildCappedBuyOrders(VR_POSITION, StrategyTicker.TQQQ, TODAY, new BigDecimal("52.50"));
        verifyNoInteractions(orderPort, orderPlanner, infiniteStrategy);
    }

    @Test
    void prepareForAllocation_vrCap_nullVrPosition_returnsOriginalOrders() {
        // vrPosition이 null(재계산 skip 케이스)이면 INFINITE_POSITION의 position==null과 동일한 원칙으로 원본 유지
        List<PlannedOrder> orders = List.of(vrLadderBuy("8500.00", 1).toPlanned(), vrLadderSell("11500.00", 1).toPlanned());

        List<PlannedOrder> prepared = capper().prepareForAllocation(
                orders, new BigDecimal("50.00"), null, null, StrategyTicker.TQQQ,
                CycleOrderStrategy.PriceCapMode.VR_POSITION, TODAY);

        assertThat(prepared).isSameAs(orders);
        verifyNoInteractions(orderPort, orderPlanner, infiniteStrategy, vrStrategy);
    }

    // 리뷰 지적 회귀 재현/방지 테스트: bootstrap(LOC+AT_CLOSE, value=0) 주문이 VR_POSITION 경로를 타면서
    // 사다리 공식(buildCappedBuyOrders)으로 잘못 재산정되던 버그. mock이 아닌 실제 VrStrategy로 검증해야
    // 가드가 제거되는 회귀를 확실히 잡는다(mock이면 가드가 없어도 통과해버림).
    @Test
    void prepareForAllocation_vrBootstrapBuy_notRegeneratedAsLadder() {
        VrStrategy realVrStrategy = new VrStrategy();
        BuyOrderPriceCapper realCapper = new BuyOrderPriceCapper(
                orderPort, orderPlanner, infiniteStrategy, realVrStrategy, strategyCyclePort);

        // V=0, pool>0 bootstrap 포지션
        VrPosition bootstrapPosition = new VrPosition(
                new AccountBalance(0, null, new BigDecimal("10000.00")),
                BigDecimal.ZERO, new BigDecimal("15.00"), new BigDecimal("5000.00"), BigDecimal.ZERO, 0);
        // referencePrice=100.00×1.05=105.00 — VrStrategy가 실제로 생성하는 bootstrap 주문 그대로 사용
        PlannedOrder bootstrapBuy = realVrStrategy.buildOrders(bootstrapPosition, StrategyTicker.TQQQ,
                new BigDecimal("100.00"), null, TODAY).getFirst();
        assertThat(bootstrapBuy.orderType()).isEqualTo(OrderType.LOC); // 픽스처 전제 확인
        assertThat(bootstrapBuy.timing()).isEqualTo(OrderTiming.AT_CLOSE);
        assertThat(bootstrapBuy.price()).isEqualByComparingTo("105.00");

        // currentPrice=90.00 → cap=94.50 < 105.00(bootstrap 가격) → cap 로직이 트리거되는 조건
        List<PlannedOrder> prepared = realCapper.prepareForAllocation(
                List.of(bootstrapBuy), new BigDecimal("90.00"), null, bootstrapPosition, StrategyTicker.TQQQ,
                CycleOrderStrategy.PriceCapMode.VR_POSITION, TODAY);

        // 가드가 없었다면 value=0 → lowerBand=0 → 사다리 전부 0원으로 재계산돼 qty=19 LIMIT/AT_OPEN 주문으로
        // 뭉개졌을 것이다 — bootstrap 주문이 원본 그대로 보존되는지 확인
        assertThat(prepared).containsExactly(bootstrapBuy);
        assertThat(prepared.getFirst().orderType()).isEqualTo(OrderType.LOC);
        assertThat(prepared.getFirst().timing()).isEqualTo(OrderTiming.AT_CLOSE);
        assertThat(prepared.getFirst().price()).isEqualByComparingTo("105.00");
    }

    @Test
    void capVrIfNeeded_vrBootstrapBuy_skipsCorrectionEntirely() {
        VrStrategy realVrStrategy = new VrStrategy();
        BuyOrderPriceCapper realCapper = new BuyOrderPriceCapper(
                orderPort, orderPlanner, infiniteStrategy, realVrStrategy, strategyCyclePort);

        VrPosition bootstrapPosition = new VrPosition(
                new AccountBalance(0, null, new BigDecimal("10000.00")),
                BigDecimal.ZERO, new BigDecimal("15.00"), new BigDecimal("5000.00"), BigDecimal.ZERO, 0);
        PlannedOrder bootstrapBuy = realVrStrategy.buildOrders(bootstrapPosition, StrategyTicker.TQQQ,
                new BigDecimal("100.00"), null, TODAY).getFirst();
        // orderPort.findPlannedByCycleAndDate는 영속 Order를 반환한다 — 커널 산출을 승격해 스텁한다
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(Order.fromPlanned(bootstrapBuy, null, null)));

        realCapper.capVrIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("90.00"), bootstrapPosition, StrategyTicker.TQQQ);

        // bootstrap 주문은 접수 전 보정 대상이 아니므로 취소·재저장이 전혀 발생하지 않아야 한다
        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void noBuyOrders_doesNothing() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        capper().capIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), POSITION);

        // 사이클 락은 조회 전에 항상 선행 — 보정 대상 없음과 무관하게 호출됨
        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(infiniteStrategy, never()).buildCappedBuyOrders(any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void allBuysWithinCap_doesNothing() {
        // cap = 50 × 1.05 = 52.50 — 모든 BUY가 cap 이하라 보정 불필요
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("50.00", 18)));

        capper().capIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), POSITION);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(infiniteStrategy, never()).buildCappedBuyOrders(any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void buysExceedCap_delegatesToStrategyAndPersistsResult() {
        // cap = 50 × 1.05 = 52.50
        List<Order> buyOrders = List.of(buy("60.00", 1), buy("52.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        // applyCapIfNeeded가 영속 Order를 커널 호출 전 PlannedOrder로 강등한다 — 매처도 강등된 값과 맞춰야 한다
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        List<PlannedOrder> capped = List.of(buy("52.50", 9).toPlanned(), buy("52.00", 11).toPlanned());
        when(infiniteStrategy.buildCappedBuyOrders(eq(POSITION), eq(TODAY), eq(plannedBuyOrders), any()))
                .thenReturn(capped);

        capper().capIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), POSITION);

        // 락 획득이 조회·취소·재저장보다 먼저 일어나는지까지 순서 검증 (동시성 직렬화 의도 반영)
        InOrder inOrder = inOrder(strategyCyclePort, orderPort, orderPlanner);
        inOrder.verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        inOrder.verify(orderPort).findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
        inOrder.verify(orderPort, times(2)).markCancelled(isNull()); // 테스트 buy()의 id=null
        inOrder.verify(orderPlanner).savePlannedOrders(any(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));

        verify(infiniteStrategy).buildCappedBuyOrders(eq(POSITION), eq(TODAY), eq(plannedBuyOrders), capCaptor.capture());
        assertThat(capCaptor.getValue()).isEqualByComparingTo("52.50");
        verify(orderPlanner).savePlannedOrders(ordersCaptor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        assertThat(ordersCaptor.getValue()).isEqualTo(capped);
    }

    @Test
    void cappedResultEmpty_deletesWithoutSaving() {
        List<Order> buyOrders = List.of(buy("200.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        when(infiniteStrategy.buildCappedBuyOrders(eq(POSITION), eq(TODAY), eq(plannedBuyOrders), any()))
                .thenReturn(List.of());

        capper().capIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), POSITION);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(orderPort).markCancelled(isNull()); // 테스트 buy()의 id=null
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    // ─── PRIVACY 캡 (capPrivacyIfNeeded) ────────────────────────────────────────

    @Test
    void capPrivacyIfNeeded_noBuyOrders_doesNothing() {
        // PLANNED 주문이 없으면 아무 작업도 하지 않음
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        capper().capPrivacyIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"));

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void capPrivacyIfNeeded_allBuysWithinCap_doesNothing() {
        // cap = 50 × 1.05 = 52.50 — BUY 가격 50.00 ≤ 52.50 → 보정 불필요
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("50.00", 5)));

        capper().capPrivacyIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"));

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void capPrivacyIfNeeded_buysExceedCap_capsToCurrentPriceX105KeepingQuantity() {
        // currentPrice=30, cap=31.50 — FIDA 가격 40.00만 cap 초과, 28.00은 cap 이하라 그대로 유지
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("40.00", 5), buy("28.00", 3)));

        capper().capPrivacyIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("30.00"));

        // 락 획득이 조회·취소·재저장보다 먼저 일어나는지까지 순서 검증
        InOrder inOrder = inOrder(strategyCyclePort, orderPort, orderPlanner);
        inOrder.verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        inOrder.verify(orderPort).findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
        // cap 초과 주문(40.00) 1건만 CANCELLED, cap 이하(28.00)는 건드리지 않음
        inOrder.verify(orderPort, times(1)).markCancelled(isNull());
        inOrder.verify(orderPlanner).savePlannedOrders(any(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));

        ArgumentCaptor<List<PlannedOrder>> captor = ArgumentCaptor.forClass(List.class);
        verify(orderPlanner).savePlannedOrders(captor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        List<PlannedOrder> saved = captor.getValue();
        // 40.00 → 31.50으로 보정, 수량 5 유지 (1건만 재저장)
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).price()).isEqualByComparingTo("31.50");
        assertThat(saved.get(0).quantity()).isEqualTo(5);
    }

    // ─── VR 캡 (capVrIfNeeded) ───────────────────────────────────────────────────

    @Test
    void capVrIfNeeded_noBuyOrders_doesNothing() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        capper().capVrIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), VR_POSITION, StrategyTicker.TQQQ);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(vrStrategy, never()).buildCappedBuyOrders(any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void capVrIfNeeded_allBuysWithinCap_doesNothing() {
        // cap = 50 × 1.05 = 52.50 — 모든 BUY가 cap 이하라 보정 불필요 (사다리 형태: LIMIT+AT_OPEN)
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(vrLadderBuy("50.00", 1)));

        capper().capVrIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), VR_POSITION, StrategyTicker.TQQQ);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(vrStrategy, never()).buildCappedBuyOrders(any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void capVrIfNeeded_buysExceedCap_delegatesToVrStrategyAndPersistsResult() {
        // cap = 50 × 1.05 = 52.50 — 사다리 형태(LIMIT+AT_OPEN)라야 VR_POSITION 보정 대상이 된다
        List<Order> buyOrders = List.of(vrLadderBuy("8500.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> capped = List.of(vrLadderBuy("52.50", 2).toPlanned());
        when(vrStrategy.buildCappedBuyOrders(eq(VR_POSITION), eq(StrategyTicker.TQQQ), eq(TODAY), any()))
                .thenReturn(capped);

        capper().capVrIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), VR_POSITION, StrategyTicker.TQQQ);

        // 락 획득이 조회·취소·재저장보다 먼저 일어나는지까지 순서 검증 — VR도 기존 사이클 동시 보정 직렬화 원칙 동일
        InOrder inOrder = inOrder(strategyCyclePort, orderPort, orderPlanner);
        inOrder.verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        inOrder.verify(orderPort).findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
        inOrder.verify(orderPort, times(1)).markCancelled(isNull()); // 테스트 vrLadderBuy()의 id=null
        inOrder.verify(orderPlanner).savePlannedOrders(any(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));

        verify(vrStrategy).buildCappedBuyOrders(eq(VR_POSITION), eq(StrategyTicker.TQQQ), eq(TODAY), capCaptor.capture());
        assertThat(capCaptor.getValue()).isEqualByComparingTo("52.50");
        verify(orderPlanner).savePlannedOrders(ordersCaptor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        assertThat(ordersCaptor.getValue()).isEqualTo(capped);
    }

    @Test
    void capVrIfNeeded_cappedResultEmpty_deletesWithoutSaving() {
        List<Order> buyOrders = List.of(vrLadderBuy("8500.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        when(vrStrategy.buildCappedBuyOrders(eq(VR_POSITION), eq(StrategyTicker.TQQQ), eq(TODAY), any()))
                .thenReturn(List.of());

        capper().capVrIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), VR_POSITION, StrategyTicker.TQQQ);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(orderPort).markCancelled(isNull()); // 테스트 vrLadderBuy()의 id=null
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }
}
