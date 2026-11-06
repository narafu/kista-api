package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.VrCycleOrderStrategy;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyLedgerBudgetTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    CyclePositionPort positionPort = mock(CyclePositionPort.class);
    OrderPort orderPort = mock(OrderPort.class);
    TradingBatchRunPort batchRunPort = mock(TradingBatchRunPort.class);
    StrategyLedgerBudget budget = new StrategyLedgerBudget(positionPort, orderPort, batchRunPort, new CycleOrderStrategies(List.of(
            new InfiniteCycleOrderStrategy(null, null), new PrivacyCycleOrderStrategy(null),
            new VrCycleOrderStrategy(null), new DcaCycleOrderStrategy())));

    private static PlannedOrder order(OrderDirection dir, OrderType type, int qty, String price, String leg) {
        return PlannedOrder.of(DAY, StrategyTicker.SOXL, type, dir, qty, new BigDecimal(price), OrderTiming.AT_CLOSE, leg);
    }

    private static PlannedOrder buy(int qty, String price, String leg) {
        return order(OrderDirection.BUY, OrderType.LOC, qty, price, leg);
    }

    private static StrategyCycle cycle(String startAmount) {
        return new StrategyCycle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal(startAmount), null, DAY, null, null, null);
    }

    private static Order saved(StrategyCycle c, OrderDirection dir, OrderStatus status, int qty, String price) {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), c.id(), DAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, dir, "L", BigDecimal.valueOf(qty), new BigDecimal(price), status, null, null, null);
    }

    @Test
    void buyWithinLedgerKeepsOrders() {
        List<PlannedOrder> orders = List.of(buy(3, "101.44", "PRIVACY_BUY_01"));
        StrategyLedgerBudget.LedgerFit fit = budget.fit(StrategyType.PRIVACY, orders, new BigDecimal("500"));
        assertThat(fit.orders()).isEqualTo(orders);
        assertThat(fit.trimmed()).isFalse();
        assertThat(fit.rejected()).isFalse();
    }

    @Test
    void privacyOverLedgerRejectsAllBuysKeepsSells() {
        PlannedOrder sell = order(OrderDirection.SELL, OrderType.LIMIT, 1, "110.00", "PRIVACY_SELL_01");
        StrategyLedgerBudget.LedgerFit fit = budget.fit(StrategyType.PRIVACY,
                List.of(buy(3, "101.44", "PRIVACY_BUY_01"), sell), new BigDecimal("177.61"));
        assertThat(fit.orders()).containsExactly(sell);
        assertThat(fit.requiredBuyUsd()).isEqualByComparingTo("304.32");
        assertThat(fit.rejected()).isTrue();
    }

    @Test
    void infiniteOverLedgerDropsCorrectionsFirst() {
        PlannedOrder base = buy(10, "20.00", "INFINITE_BUY_01");
        List<PlannedOrder> orders = List.of(base, buy(1, "9.00", "INFINITE_CORRECTION_01"),
                buy(1, "9.00", "INFINITE_CORRECTION_02"), buy(1, "9.00", "INFINITE_CORRECTION_03"));
        StrategyLedgerBudget.LedgerFit fit = budget.fit(StrategyType.INFINITE, orders, new BigDecimal("210"));
        assertThat(fit.orders()).extracting(PlannedOrder::orderLeg)
                .containsExactly("INFINITE_BUY_01", "INFINITE_CORRECTION_01");
        assertThat(fit.trimmed()).isTrue();
        assertThat(fit.rejected()).isFalse();
    }

    @Test
    void vrBootstrapOverLedgerRejected() {
        StrategyLedgerBudget.LedgerFit fit = budget.fit(StrategyType.VR,
                List.of(buy(5, "50.00", "VR_BOOTSTRAP_01")), new BigDecimal("100"));
        assertThat(fit.rejected()).isTrue();
        assertThat(fit.orders()).isEmpty();
    }

    @Test
    void dcaAppliesLedger() {
        // DCA도 시드 점유 전략 — 원장 예수금 안이면 통과, 넘으면 BUY 거절
        List<PlannedOrder> orders = List.of(buy(1, "100.00", "DCA_X"));
        assertThat(budget.applies(StrategyType.DCA)).isTrue();
        assertThat(budget.fit(StrategyType.DCA, orders, new BigDecimal("100.00")).rejected()).isFalse();
        StrategyLedgerBudget.LedgerFit over = budget.fit(StrategyType.DCA, orders, BigDecimal.ZERO);
        assertThat(over.rejected()).isTrue();
        assertThat(over.orders()).isEmpty();
    }

    @Test
    void remainingFallsBackToStartAmountWhenNoPosition() {
        StrategyCycle c = cycle("1000");
        when(positionPort.findLatestOne(c.id())).thenReturn(Optional.empty());
        when(orderPort.findPlannedOrPlacedByCycleAndDate(c.id(), DAY)).thenReturn(List.of());
        assertThat(budget.remaining(c, DAY)).isEqualByComparingTo("1000");
    }

    @Test
    void remainingSubtractsSameDayCycleBuys() {
        StrategyCycle c = cycle("1000");
        when(positionPort.findLatestOne(c.id())).thenReturn(Optional.of(
                new CyclePosition(null, c.id(), new BigDecimal("1000"), null, null, BigDecimal.ZERO, null, null)));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(c.id(), DAY)).thenReturn(List.of(
                saved(c, OrderDirection.BUY, OrderStatus.PLACED, 3, "100"),
                saved(c, OrderDirection.BUY, OrderStatus.SUBMITTING, 1, "100"),
                saved(c, OrderDirection.SELL, OrderStatus.PLACED, 1, "50")));
        assertThat(budget.remaining(c, DAY)).isEqualByComparingTo("600");
    }

    private static Order cancelledBuy(StrategyCycle c, int qty, String price, String ext, String leg, String filledQty, String filledPrice) {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), c.id(), DAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, OrderDirection.BUY, leg, BigDecimal.valueOf(qty), new BigDecimal(price), OrderStatus.CANCELLED, ext,
                filledQty == null ? null : new BigDecimal(filledQty), filledPrice == null ? null : new BigDecimal(filledPrice));
    }

    private BigDecimal remainingWithCancelled(StrategyCycle c, Order... cancelled) {
        when(positionPort.findLatestOne(c.id())).thenReturn(Optional.of(
                new CyclePosition(null, c.id(), new BigDecimal("1000"), null, null, BigDecimal.ZERO, null, null)));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(c.id(), DAY)).thenReturn(List.of());
        when(orderPort.findCancelledByCycleAndDate(c.id(), DAY)).thenReturn(List.of(cancelled));
        return budget.remaining(c, DAY);
    }

    @Test
    void remainingSubtractsRecordedFillOfCancelledBuyBeforeReport() {
        StrategyCycle c = cycle("1000");
        when(batchRunPort.isReported(DAY, c.strategyId())).thenReturn(false);
        // 3주 체결 @ 9.50 = 28.50 — 주문 금액 전체(100)가 아니라 체결분만, 미체결(0, price null)은 0
        assertThat(remainingWithCancelled(c,
                cancelledBuy(c, 10, "10.00", "E1", "L", "3", "9.50"),
                cancelledBuy(c, 10, "10.00", "E2", "L", "0", null))).isEqualByComparingTo("971.50");
    }

    @Test
    void remainingChargesFullAmountWhenCancelledFillUnknown() {
        StrategyCycle c = cycle("1000");
        when(batchRunPort.isReported(DAY, c.strategyId())).thenReturn(false);
        assertThat(remainingWithCancelled(c, cancelledBuy(c, 10, "10.00", "E1", "L", null, null))).isEqualByComparingTo("900");
    }

    @Test
    void remainingIgnoresCancelledAfterReport() {
        StrategyCycle c = cycle("1000");
        when(batchRunPort.isReported(DAY, c.strategyId())).thenReturn(true);
        assertThat(remainingWithCancelled(c, cancelledBuy(c, 10, "10.00", "E1", "L", "3", "9.50"))).isEqualByComparingTo("1000");
        verify(orderPort, never()).findCancelledByCycleAndDate(c.id(), DAY);
    }

    @Test
    void remainingIgnoresSellUnplacedAndUnknownLegCancelled() {
        StrategyCycle c = cycle("1000");
        when(batchRunPort.isReported(DAY, c.strategyId())).thenReturn(false);
        Order sell = saved(c, OrderDirection.SELL, OrderStatus.CANCELLED, 10, "10.00");
        assertThat(remainingWithCancelled(c, sell,
                cancelledBuy(c, 10, "10.00", null, "L", null, null),                       // 접수 전 취소
                cancelledBuy(c, 10, "10.00", "E1", Order.UNKNOWN_LEG, null, null)))        // 수동 보정 행
                .isEqualByComparingTo("1000");
    }

    @Test
    void repricedReorderWithZeroFillOriginalDoesNotDoubleCharge() {
        StrategyCycle c = cycle("1000");
        when(batchRunPort.isReported(DAY, c.strategyId())).thenReturn(false);
        when(positionPort.findLatestOne(c.id())).thenReturn(Optional.of(
                new CyclePosition(null, c.id(), new BigDecimal("1000"), null, null, BigDecimal.ZERO, null, null)));
        // 원본(0 체결, 취소) + 가격만 바꾼 재주문(PLACED 100) → 약정 100만 잡힌다
        when(orderPort.findPlannedOrPlacedByCycleAndDate(c.id(), DAY)).thenReturn(List.of(saved(c, OrderDirection.BUY, OrderStatus.PLACED, 10, "10")));
        when(orderPort.findCancelledByCycleAndDate(c.id(), DAY)).thenReturn(List.of(cancelledBuy(c, 10, "10.00", "E1", "L", "0", null)));
        assertThat(budget.remaining(c, DAY)).isEqualByComparingTo("900");
    }
}
