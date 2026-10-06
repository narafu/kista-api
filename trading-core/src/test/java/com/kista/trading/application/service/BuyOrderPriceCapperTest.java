package com.kista.trading.application.service;
import com.kista.trading.application.service.support.TradingOrderPlanner;

import com.kista.sharedkernel.OrderStatus;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.TradingAccount;
import com.kista.sharedkernel.Broker;
import com.kista.trading.domain.model.Order;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.matching.domain.model.AccountBalance;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.matching.domain.model.VrPosition;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import org.junit.jupiter.api.BeforeEach;
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
import com.kista.sharedkernel.StrategyType;

// BUY PLANNED 가격이 currentPrice × 1.05 초과 시 전략별 재산정 위임 후 영속화 — I/O 오케스트레이션만 검증
// 캡 가격 재산정 공식(병합/보정 등)은 InfiniteStrategyTypeTest.buildCappedBuyOrders / VrStrategyTypeTest.buildCappedBuyOrders 참고
// CycleOrderStrategy 자체를 mock해 capBuyOrders/capsIndividualOrders만 stub한다 — 실제 계산 로직 검증은 이 파일의 책임이 아니다
@ExtendWith(MockitoExtension.class)
class BuyOrderPriceCapperTest {

    @Mock OrderPort orderPort;
    @Mock TradingOrderPlanner orderPlanner;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CycleOrderStrategy infiniteType;
    @Mock CycleOrderStrategy privacyType;
    @Mock CycleOrderStrategy vrType;
    @Captor ArgumentCaptor<List<PlannedOrder>> ordersCaptor;

    static final LocalDate TODAY = LocalDate.now();

    static final TradingAccount ACCOUNT = TradingAccount.from(new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", null,
            Broker.KIS, null));

    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();

    static final InfinitePosition POSITION = new InfinitePosition(
            new AccountBalance(0, null, new BigDecimal("20000")), StrategyTicker.SOXL, new BigDecimal("10.00"), 20);

    static final VrPosition VR_POSITION = new VrPosition(
            new AccountBalance(1, new BigDecimal("100.00"), new BigDecimal("5000.00")),
            new BigDecimal("10000.00"), new BigDecimal("15.00"), new BigDecimal("5000.00"), BigDecimal.ZERO, 0);

    BuyOrderPriceCapper capper;

    @BeforeEach
    void setUp() {
        lenient().when(infiniteType.cycleType()).thenReturn(StrategyType.INFINITE);
        lenient().when(privacyType.cycleType()).thenReturn(StrategyType.PRIVACY);
        lenient().when(vrType.cycleType()).thenReturn(StrategyType.VR);
        lenient().when(privacyType.capsIndividualOrders()).thenReturn(true);
        CycleOrderStrategies cycleOrderStrategies = new CycleOrderStrategies(List.of(infiniteType, privacyType, vrType));
        capper = new BuyOrderPriceCapper(orderPort, orderPlanner, cycleOrderStrategies, strategyCyclePort);
    }

    private Order buy(String price, int quantity) {
        return new Order(null, null, null, TODAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, OrderDirection.BUY, quantity, new BigDecimal(price), OrderStatus.PLANNED, null, null, null);
    }

    private Order sell(String price, int quantity) {
        return new Order(null, null, null, TODAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, OrderDirection.SELL, quantity, new BigDecimal(price), OrderStatus.PLANNED, null, null, null);
    }

    // ─── prepareForAllocation ─────────────────────────────────────────────

    @Test
    void prepareForAllocation_infiniteCap_returnsCappedBuysAndCorrectionsWithoutPersistence() {
        PlannedOrder originalBuy = buy("60.00", 1).toPlanned();
        PlannedOrder cappedBuy = buy("52.50", 9).toPlanned();
        PlannedOrder correction = buy("50.00", 1).toPlanned();
        when(infiniteType.capBuyOrders(eq(List.of(originalBuy)), eq(new BigDecimal("52.50")),
                eq(POSITION), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(List.of(cappedBuy, correction));

        List<PlannedOrder> prepared = capper.prepareForAllocation(
                List.of(originalBuy), new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL,
                StrategyType.INFINITE, TODAY);

        assertThat(prepared).containsExactly(cappedBuy, correction);
        verifyNoInteractions(orderPort, orderPlanner);
    }

    @Test
    void prepareForAllocation_privacyCap_changesOnlyExceedingBuyPrices() {
        PlannedOrder exceedingBuy = buy("40.00", 5).toPlanned();
        PlannedOrder sell = sell("45.00", 2).toPlanned();
        PlannedOrder withinCapBuy = buy("28.00", 3).toPlanned();
        when(privacyType.capBuyOrders(eq(List.of(exceedingBuy, withinCapBuy)), eq(new BigDecimal("31.50")),
                isNull(), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(List.of(exceedingBuy.withPrice(new BigDecimal("31.50")), withinCapBuy));

        List<PlannedOrder> prepared = capper.prepareForAllocation(
                List.of(exceedingBuy, sell, withinCapBuy), new BigDecimal("30.00"), null, null, StrategyTicker.SOXL,
                StrategyType.PRIVACY, TODAY);

        assertThat(prepared.get(0).price()).isEqualByComparingTo("31.50");
        assertThat(prepared.get(0).quantity()).isEqualTo(5);
        assertThat(prepared.get(1)).isSameAs(sell);
        assertThat(prepared.get(2)).isSameAs(withinCapBuy);
        verifyNoInteractions(orderPort, orderPlanner);
    }

    @Test
    void prepareForAllocation_noCapReturnsOriginalOrders() {
        // cap = 50 × 1.05 = 52.50 — BUY 50.00는 cap 이하라 캡 재산정 자체가 트리거되지 않는다
        List<PlannedOrder> orders = List.of(buy("50.00", 1).toPlanned(), sell("70.00", 1).toPlanned());

        List<PlannedOrder> prepared = capper.prepareForAllocation(
                orders, new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL, StrategyType.INFINITE, TODAY);

        assertThat(prepared).isSameAs(orders);
        verifyNoInteractions(orderPort, orderPlanner);
        verify(infiniteType, never()).capBuyOrders(any(), any(), any(), any(), any(), any());
    }

    @Test
    void prepareForAllocation_currentPriceNull_returnsOriginalOrders() {
        List<PlannedOrder> orders = List.of(buy("60.00", 1).toPlanned(), sell("70.00", 1).toPlanned());

        List<PlannedOrder> prepared = capper.prepareForAllocation(
                orders, null, POSITION, null, StrategyTicker.SOXL, StrategyType.INFINITE, TODAY);

        assertThat(prepared).isSameAs(orders);
        verifyNoInteractions(orderPort, orderPlanner);
        // setUp()의 CycleOrderStrategies 조립이 이미 각 mock의 cycleType()을 1회 호출해두므로
        // verifyNoInteractions(infiniteType) 대신 capBuyOrders 미호출만 좁혀서 검증한다
        verify(infiniteType, never()).capBuyOrders(any(), any(), any(), any(), any(), any());
    }

    @Test
    void prepareForAllocation_vrCap_returnsCappedBuysWithoutPersistence() {
        PlannedOrder originalBuy = buy("8500.00", 1).toPlanned();
        PlannedOrder sell = sell("11500.00", 1).toPlanned();
        PlannedOrder cappedBuy = buy("525.00", 2).toPlanned();
        when(vrType.capBuyOrders(eq(List.of(originalBuy)), eq(new BigDecimal("52.50")),
                isNull(), eq(VR_POSITION), eq(StrategyTicker.TQQQ), eq(TODAY)))
                .thenReturn(List.of(cappedBuy));

        List<PlannedOrder> prepared = capper.prepareForAllocation(
                List.of(originalBuy, sell), new BigDecimal("50.00"), null, VR_POSITION, StrategyTicker.TQQQ,
                StrategyType.VR, TODAY);

        assertThat(prepared).containsExactly(cappedBuy, sell);
        verifyNoInteractions(orderPort, orderPlanner);
    }

    // ─── capIfNeeded — INFINITE/VR(전체 취소·재저장) ────────────────────────

    @Test
    void capIfNeeded_noBuyOrders_doesNothing() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL, null);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(infiniteType, never()).capBuyOrders(any(), any(), any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void capIfNeeded_allBuysWithinCap_doesNothing() {
        // cap = 50 × 1.05 = 52.50 — 모든 BUY가 cap 이하라 보정 불필요
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("50.00", 18)));

        capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL, null);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(infiniteType, never()).capBuyOrders(any(), any(), any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void capIfNeeded_bootstrapUnchangedResult_skipsCorrectionEntirely() {
        // VR bootstrap 등 capBuyOrders가 입력을 그대로 반환하는 경우 — 취소·재저장 전혀 발생하지 않아야 한다
        List<Order> buyOrders = List.of(buy("8500.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        when(vrType.capBuyOrders(eq(plannedBuyOrders), any(), isNull(), eq(VR_POSITION), eq(StrategyTicker.TQQQ), eq(TODAY)))
                .thenReturn(plannedBuyOrders); // 변경 없음 — bootstrap 스킵을 흉내

        capper.capIfNeeded(StrategyType.VR, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("90.00"), null, VR_POSITION, StrategyTicker.TQQQ, null);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void buysExceedCap_delegatesToStrategyAndPersistsResult() {
        // cap = 50 × 1.05 = 52.50
        List<Order> buyOrders = List.of(buy("60.00", 1), buy("52.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        List<PlannedOrder> capped = List.of(buy("52.50", 9).toPlanned(), buy("52.00", 11).toPlanned());
        when(infiniteType.capBuyOrders(eq(plannedBuyOrders), any(), eq(POSITION), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(capped);

        // 재캡 총액(1044.50)이 원본(112.00)보다 커지므로 live 예산이 필요 — 충분한 예산이면 재캡 결과 그대로 반영
        boolean budgetRequired = capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL, new BigDecimal("10000.00"));

        assertThat(budgetRequired).isFalse();
        InOrder inOrder = inOrder(strategyCyclePort, orderPort, orderPlanner);
        inOrder.verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        inOrder.verify(orderPort).findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
        inOrder.verify(orderPort, times(2)).markCancelled(isNull()); // 테스트 buy()의 id=null
        inOrder.verify(orderPlanner).savePlannedOrders(any(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));

        verify(orderPlanner).savePlannedOrders(ordersCaptor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        assertThat(ordersCaptor.getValue()).isEqualTo(capped);
    }

    @Test
    void cappedResultEmpty_deletesWithoutSaving() {
        List<Order> buyOrders = List.of(buy("200.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        when(infiniteType.capBuyOrders(eq(plannedBuyOrders), any(), eq(POSITION), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(List.of());

        capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL, null);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(orderPort).markCancelled(isNull());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    // ─── capIfNeeded — PRIVACY(개별 취소·재저장) ─────────────────────────────

    @Test
    void capPrivacyIfNeeded_buysExceedCap_capsToCurrentPriceX105KeepingQuantity() {
        // currentPrice=30, cap=31.50 — FIDA 가격 40.00만 cap 초과, 28.00은 cap 이하라 그대로 유지
        List<Order> buyOrders = List.of(buy("40.00", 5), buy("28.00", 3));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        PlannedOrder cappedFirst = plannedBuyOrders.get(0).withPrice(new BigDecimal("31.50"));
        when(privacyType.capBuyOrders(eq(plannedBuyOrders), eq(new BigDecimal("31.50")), isNull(), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(List.of(cappedFirst, plannedBuyOrders.get(1))); // 두 번째는 변경 없음(동일 값)

        capper.capIfNeeded(StrategyType.PRIVACY, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("30.00"), null, null, StrategyTicker.SOXL, null);

        InOrder inOrder = inOrder(strategyCyclePort, orderPort, orderPlanner);
        inOrder.verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        inOrder.verify(orderPort).findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
        inOrder.verify(orderPort, times(1)).markCancelled(isNull()); // 40.00짜리 1건만 취소
        inOrder.verify(orderPlanner).savePlannedOrders(any(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));

        verify(orderPlanner).savePlannedOrders(ordersCaptor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        List<PlannedOrder> saved = ordersCaptor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).price()).isEqualByComparingTo("31.50");
        assertThat(saved.get(0).quantity()).isEqualTo(5);
    }

    @Test
    void capPrivacyIfNeeded_allBuysWithinCap_doesNothing() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("50.00", 5)));

        capper.capIfNeeded(StrategyType.PRIVACY, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), null, null, StrategyTicker.SOXL, null);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(privacyType, never()).capBuyOrders(any(), any(), any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    // ─── capIfNeeded — live 예산 가드(재캡 총액 증가 시) ─────────────────────

    @Test
    void capIfNeeded_totalIncreasesWithoutBudget_requestsBudgetWithoutPersisting() {
        // 원본 60×1=60 → 재캡 52.5×2=105 — 총액 증가인데 예산이 없으면 반영하지 않고 호출부에 live 예산을 요청한다
        List<Order> buyOrders = List.of(buy("60.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        when(infiniteType.capBuyOrders(any(), any(), eq(POSITION), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(List.of(buy("52.50", 2).toPlanned()));

        boolean budgetRequired = capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL, null);

        assertThat(budgetRequired).isTrue();
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void capIfNeeded_totalNotIncreased_appliesWithoutBudget() {
        // 원본 60×2=120 → 재캡 52.5×2=105 — 총액이 늘지 않으면 allocator 승인 범위 안이라 예산 없이 반영
        List<Order> buyOrders = List.of(buy("60.00", 2));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> capped = List.of(buy("52.50", 2).toPlanned());
        when(infiniteType.capBuyOrders(any(), any(), eq(POSITION), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(capped);

        boolean budgetRequired = capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL, null);

        assertThat(budgetRequired).isFalse();
        verify(orderPlanner).savePlannedOrders(capped, ACCOUNT, STRATEGY_CYCLE_ID);
    }

    @Test
    void capIfNeeded_overBudget_persistsFittedOrders() {
        // 원본 60×1=60, 계좌 여유 40 → 예산 100. 재캡 base 52.5 + 보정 50 = 102.5 > 100 → 보정 생략안(52.5)만 반영
        List<Order> buyOrders = List.of(buy("60.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        PlannedOrder base = buy("52.50", 1).toPlanned();
        List<PlannedOrder> capped = List.of(base, buy("50.00", 1).toPlanned());
        when(infiniteType.capBuyOrders(any(), any(), eq(POSITION), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(capped);
        when(infiniteType.fitBuysToBudget(capped, new BigDecimal("100.00"))).thenReturn(List.of(base));

        capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL, new BigDecimal("40.00"));

        verify(orderPort).markCancelled(isNull());
        verify(orderPlanner).savePlannedOrders(List.of(base), ACCOUNT, STRATEGY_CYCLE_ID);
    }

    @Test
    void capIfNeeded_fittedStillOverBudget_keepsOriginalOrders() {
        // 예산 = 여유 0 + 원본 60 = 60. 축소안도 105 > 60 — 재캡을 생략하고 allocator가 승인한 원본을 그대로 둔다
        List<Order> buyOrders = List.of(buy("60.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> capped = List.of(buy("52.50", 2).toPlanned());
        when(infiniteType.capBuyOrders(any(), any(), eq(POSITION), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(capped);
        when(infiniteType.fitBuysToBudget(any(), any())).thenReturn(capped);

        capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL, BigDecimal.ZERO);

        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }
}
