package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.OrderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// BUY PLANNED 가격이 currentPrice × 1.10 초과 시 가격 캡 + 수량 재산정 검증
// K(단위금액)는 position.unitAmount() = totalAssets ÷ 20 / SOXL targetProfitRate = 0.20
@ExtendWith(MockitoExtension.class)
class BuyOrderPriceCapperTest {

    @Mock OrderPort orderPort;
    @Mock TradingOrderPlanner orderPlanner;
    @Captor ArgumentCaptor<List<Order>> ordersCaptor;

    static final LocalDate TODAY = LocalDate.now();

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", "01",
            Account.Broker.KIS);

    static final Strategy CYCLE = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);

    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();

    private BuyOrderPriceCapper capper() {
        return new BuyOrderPriceCapper(orderPort, orderPlanner);
    }

    // holdings=0 → unitAmount = usdDeposit ÷ 20 (= K)
    private InfinitePosition positionWithUnitAmount(String usdDeposit) {
        return new InfinitePosition(
                new AccountBalance(0, null, new BigDecimal(usdDeposit)),
                Ticker.SOXL, new BigDecimal("10.00"));
    }

    private Order buy(String price, int quantity) {
        return new Order(null, null, null, TODAY, Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, quantity, new BigDecimal(price), Order.OrderStatus.PLANNED, null, null, null);
    }

    @Test
    void noBuyOrders_doesNothing() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        capper().capIfNeeded(TODAY, CYCLE, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"),
                positionWithUnitAmount("20000"));

        verify(orderPort, never()).deletePlannedBuyByCycleAndDate(any(), any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void allBuysWithinCap_doesNothing() {
        // cap = 50 × 1.10 = 55.00 — 모든 BUY가 cap 이하라 보정 불필요
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("50.00", 18)));

        capper().capIfNeeded(TODAY, CYCLE, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"),
                positionWithUnitAmount("20000"));

        verify(orderPort, never()).deletePlannedBuyByCycleAndDate(any(), any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void twoBuys_distinctCaps_recalculatesBoth() {
        // cap=55, K=1000, r=0.20 / buy①=60(>cap→55), buy②=52(≤cap)
        // qty1 = floor((1000/2) / 55) = 9 / remaining = (1000-55×9)×1.20 = 606 / qty2 = floor(606/52) = 11
        // 보정 3회: K/(20+1)=47.62, K/(21+1)=45.45, K/(22+1)=43.48 — 각 1주 LOC
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("60.00", 1), buy("52.00", 1)));

        capper().capIfNeeded(TODAY, CYCLE, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"),
                positionWithUnitAmount("20000"));

        verify(orderPort).deletePlannedBuyByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
        verify(orderPlanner).savePlannedOrders(ordersCaptor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        List<Order> saved = ordersCaptor.getValue();
        assertThat(saved).hasSize(5);
        assertThat(saved.get(0).quantity()).isEqualTo(9);
        assertThat(saved.get(0).price()).isEqualByComparingTo("55.00");
        assertThat(saved.get(1).quantity()).isEqualTo(11);
        assertThat(saved.get(1).price()).isEqualByComparingTo("52.00");
        assertThat(saved.get(2).quantity()).isEqualTo(1);
        assertThat(saved.get(2).price()).isEqualByComparingTo("47.62");
        assertThat(saved.get(3).quantity()).isEqualTo(1);
        assertThat(saved.get(3).price()).isEqualByComparingTo("45.45");
        assertThat(saved.get(4).quantity()).isEqualTo(1);
        assertThat(saved.get(4).price()).isEqualByComparingTo("43.48");
    }

    @Test
    void twoBuys_convergeToSameCap_mergesIntoOne() {
        // cap=55, buy①=70·buy②=60 모두 55로 캡 → 동일 가격이면 병합
        // qty1 = floor(500/55) = 9 / remaining = 606 / qty2 = floor(606/55) = 11 / merged = 20
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("70.00", 1), buy("60.00", 1)));

        capper().capIfNeeded(TODAY, CYCLE, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"),
                positionWithUnitAmount("20000"));

        verify(orderPort).deletePlannedBuyByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
        verify(orderPlanner).savePlannedOrders(ordersCaptor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        List<Order> saved = ordersCaptor.getValue();
        // 병합 1건 + 보정 3회: K/(20+1)=47.62, K/(21+1)=45.45, K/(22+1)=43.48 — 각 1주 LOC
        assertThat(saved).hasSize(4);
        assertThat(saved.getFirst().quantity()).isEqualTo(20);
        assertThat(saved.getFirst().price()).isEqualByComparingTo("55.00");
        assertThat(saved.get(1).price()).isEqualByComparingTo("47.62");
        assertThat(saved.get(2).price()).isEqualByComparingTo("45.45");
        assertThat(saved.get(3).price()).isEqualByComparingTo("43.48");
    }

    @Test
    void singleBuy_capped_recalculatesQuantity() {
        // 후반 단일 LOC 매수 — cap=55, K=1000 / qty = floor(1000/55) = 18
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("70.00", 1)));

        capper().capIfNeeded(TODAY, CYCLE, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"),
                positionWithUnitAmount("20000"));

        verify(orderPort).deletePlannedBuyByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
        verify(orderPlanner).savePlannedOrders(ordersCaptor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        List<Order> saved = ordersCaptor.getValue();
        // 재산정 1건 + 보정 3회: K/(18+1)=52.63, K/(19+1)=50.00, K/(20+1)=47.62 — 각 1주 LOC
        assertThat(saved).hasSize(4);
        assertThat(saved.getFirst().quantity()).isEqualTo(18);
        assertThat(saved.getFirst().price()).isEqualByComparingTo("55.00");
        assertThat(saved.get(1).price()).isEqualByComparingTo("52.63");
        assertThat(saved.get(2).price()).isEqualByComparingTo("50.00");
        assertThat(saved.get(3).price()).isEqualByComparingTo("47.62");
    }

    @Test
    void singleBuy_cappedQuantityZero_deletesWithoutSaving() {
        // cap=55, K=50(usdDeposit=1000) / qty = floor(50/55) = 0 → 매수 제외
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("200.00", 1)));

        capper().capIfNeeded(TODAY, CYCLE, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"),
                positionWithUnitAmount("1000"));

        verify(orderPort).deletePlannedBuyByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }
}
