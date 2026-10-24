package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.shared.kernel.*;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.ReorderBuyBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReorderBuyBudgetServiceTest {

    static final LocalDate DATE = LocalDate.of(2026, 10, 6);
    final UUID accountId = UUID.randomUUID();

    @Mock OrderPort orderPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyLedgerBudget ledgerBudget;

    private ReorderBuyBudgetService service() {
        return new ReorderBuyBudgetService(orderPort, strategyCyclePort, strategyPort, ledgerBudget);
    }

    private Order order(UUID cycleId, OrderDirection dir, OrderStatus status, LocalDate date) {
        return new Order(UUID.randomUUID(), accountId, cycleId, date, StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE, dir, BigDecimal.valueOf(10), new BigDecimal("20"), status, null, null, null);
    }

    private Order order(OrderDirection dir, OrderStatus status, LocalDate date) {
        return order(UUID.randomUUID(), dir, status, date);
    }

    // 원본 주문과 사이클을 stub — 사이클별 remaining 반환
    private void stubCycle(UUID cycleId, String remaining) {
        stubCycle(cycleId, remaining, true);
    }

    private void stubCycle(UUID cycleId, String remaining, boolean applies) {
        UUID strategyId = UUID.randomUUID();
        Strategy strategy = new Strategy(strategyId, accountId, StrategyType.PRIVACY, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.MAX);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);
        when(ledgerBudget.applies(StrategyType.PRIVACY)).thenReturn(applies);
        StrategyCycle cycle = new StrategyCycle(cycleId, strategyId, UUID.randomUUID(), new BigDecimal("1000"),
                null, DATE, null, java.time.Instant.now(), null);
        when(strategyCyclePort.findById(cycleId)).thenReturn(Optional.of(cycle));
        if (applies) when(ledgerBudget.remaining(cycle, DATE)).thenReturn(new BigDecimal(remaining));
    }

    private void stubOrders(Order... sources) {
        for (Order o : sources) when(orderPort.findById(o.id())).thenReturn(Optional.of(o));
    }

    @Test
    void returnsRemainingPerCycle() {
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        Order a = order(c1, OrderDirection.BUY, OrderStatus.PLANNED, DATE);
        Order b = order(c2, OrderDirection.BUY, OrderStatus.PLACED, DATE);
        Order a2 = order(c1, OrderDirection.BUY, OrderStatus.FILLED, DATE);
        stubOrders(a, b, a2);
        stubCycle(c1, "300");
        stubCycle(c2, "50");

        ReorderBuyBudget r = service().query(List.of(a.id(), b.id(), a2.id()), null);

        assertThat(r.cycles().keySet()).containsExactlyInAnyOrder(c1, c2);
        assertThat(r.cycles().get(c1).remaining()).isEqualByComparingTo("300");
        assertThat(r.cycles().get(c2).remaining()).isEqualByComparingTo("50");
        assertThat(r.sourceRefunds().get(a.id())).isEqualByComparingTo("200");
        assertThat(r.sourceRefunds().get(b.id())).isEqualByComparingTo("200");
        assertThat(r.sourceRefunds().get(a2.id())).isEqualByComparingTo("0");
    }

    @Test
    void nonSeedCycleOmittedFromCycles() {
        UUID c = UUID.randomUUID();
        Order a = order(c, OrderDirection.BUY, OrderStatus.PLANNED, DATE);
        stubOrders(a);
        stubCycle(c, "0", false);

        ReorderBuyBudget r = service().query(List.of(a.id()), null);

        assertThat(r.cycles()).isEmpty();
        assertThat(r.sourceRefunds().get(a.id())).isEqualByComparingTo("200");
    }

    @Test
    void 원본_SELL_FAILED_다른_거래일은_환급_0() {
        UUID c = UUID.randomUUID();
        Order sell = order(c, OrderDirection.SELL, OrderStatus.PLANNED, DATE);
        Order failed = order(c, OrderDirection.BUY, OrderStatus.FAILED, DATE);
        // 원본 거래일(어제)과 조회 거래일(DATE)이 다르면 원본이 합계에 없으므로 환급 없음
        Order other = order(c, OrderDirection.BUY, OrderStatus.PLANNED, DATE.minusDays(1));
        stubOrders(sell, failed, other);
        stubCycle(c, "300");

        ReorderBuyBudget r = service().query(List.of(sell.id(), failed.id(), other.id()), DATE);

        assertThat(r.sourceRefunds().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
    }

    @Test
    void 다른_계좌_주문이_섞이면_예외() {
        Order mine = order(OrderDirection.BUY, OrderStatus.PLANNED, DATE);
        Order foreign = new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DATE, StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, BigDecimal.valueOf(1), BigDecimal.ONE, OrderStatus.PLANNED, null, null, null);
        stubOrders(mine, foreign);

        assertThatThrownBy(() -> service().query(List.of(mine.id(), foreign.id()), null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ledgerBudget);
    }

    @Test
    void 주문이_없거나_건수_범위_밖이면_예외() {
        UUID id = UUID.randomUUID();
        when(orderPort.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().query(List.of(id), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().query(List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(ReorderBuyBudgetService.MAX_ORDERS + 1).toList();
        assertThatThrownBy(() -> service().query(tooMany, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
