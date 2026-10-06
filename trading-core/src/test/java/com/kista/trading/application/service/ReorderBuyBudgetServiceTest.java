package com.kista.trading.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.broker.application.port.output.LiveBalancePort;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderStatus;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.support.TradingFixtures;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.ReorderBuyBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReorderBuyBudgetServiceTest {

    static final LocalDate DATE = LocalDate.of(2026, 10, 6);
    final UUID accountId = UUID.randomUUID();
    final Account account = TradingFixtures.kisAccount(accountId, UUID.randomUUID());

    @Mock OrderPort orderPort;
    @Mock AccountPort accountPort;
    @Mock LiveBalancePort liveBalancePort;

    private ReorderBuyBudgetService service() {
        return new ReorderBuyBudgetService(orderPort, accountPort, liveBalancePort);
    }

    private Order order(OrderDirection dir, OrderStatus status, LocalDate date) {
        return new Order(UUID.randomUUID(), accountId, UUID.randomUUID(), date, StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE, dir, 10, new BigDecimal("20"), status, null, null, null);
    }

    private void stub(String planned, String live, Order... sources) {
        for (Order o : sources) when(orderPort.findById(o.id())).thenReturn(Optional.of(o));
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(account);
        when(orderPort.sumPlannedBuyByAccountAndDate(accountId, DATE)).thenReturn(new BigDecimal(planned));
        when(liveBalancePort.getLiveBalance(any(), any())).thenReturn(new BrokerBalance(0, BigDecimal.ZERO, new BigDecimal(live)));
    }

    @Test
    void 여러_원본을_live_1회로_조회하고_원본별_환급을_돌려준다() {
        Order planned = order(OrderDirection.BUY, OrderStatus.PLANNED, DATE);
        Order placed = order(OrderDirection.BUY, OrderStatus.PLACED, DATE);
        Order filled = order(OrderDirection.BUY, OrderStatus.FILLED, DATE);
        stub("300", "1000", planned, placed, filled);

        ReorderBuyBudget b = service().query(List.of(planned.id(), placed.id(), filled.id()), null);

        assertThat(b.plannedBuy()).isEqualByComparingTo("300");
        assertThat(b.liveOrderable()).isEqualByComparingTo("1000");
        assertThat(b.sourceRefunds().get(planned.id())).isEqualByComparingTo("200");
        assertThat(b.sourceRefunds().get(placed.id())).isEqualByComparingTo("200");
        assertThat(b.sourceRefunds().get(filled.id())).isEqualByComparingTo("0");
        verify(liveBalancePort, times(1)).getLiveBalance(any(), any());
    }

    @Test
    void 원본_SELL_FAILED_다른_거래일은_환급_0() {
        Order sell = order(OrderDirection.SELL, OrderStatus.PLANNED, DATE);
        Order failed = order(OrderDirection.BUY, OrderStatus.FAILED, DATE);
        // 원본 거래일(어제)과 조회 거래일(DATE)이 다르면 원본이 합계에 없으므로 환급 없음
        Order other = order(OrderDirection.BUY, OrderStatus.PLANNED, DATE.minusDays(1));
        stub("300", "1000", sell, failed, other);

        ReorderBuyBudget b = service().query(List.of(sell.id(), failed.id(), other.id()), DATE);

        assertThat(b.sourceRefunds().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
    }

    @Test
    void live_실패면_liveOrderable_null() {
        Order src = order(OrderDirection.BUY, OrderStatus.PLANNED, DATE);
        when(orderPort.findById(src.id())).thenReturn(Optional.of(src));
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(account);
        when(orderPort.sumPlannedBuyByAccountAndDate(accountId, DATE)).thenReturn(new BigDecimal("300"));
        when(liveBalancePort.getLiveBalance(any(), any())).thenThrow(new RuntimeException("timeout"));

        ReorderBuyBudget b = service().query(List.of(src.id()), null);

        assertThat(b.plannedBuy()).isEqualByComparingTo("300");
        assertThat(b.sourceRefunds().get(src.id())).isEqualByComparingTo("200");
        assertThat(b.liveOrderable()).isNull();
    }

    @Test
    void 합계를_live보다_먼저_조회한다() {
        Order src = order(OrderDirection.BUY, OrderStatus.FILLED, DATE);
        stub("300", "1000", src);

        service().query(List.of(src.id()), null);

        InOrder o = inOrder(orderPort, liveBalancePort);
        o.verify(orderPort).sumPlannedBuyByAccountAndDate(accountId, DATE);
        o.verify(liveBalancePort).getLiveBalance(any(), any());
    }

    @Test
    void 다른_계좌_주문이_섞이면_예외() {
        Order mine = order(OrderDirection.BUY, OrderStatus.PLANNED, DATE);
        Order foreign = new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DATE, StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, 1, BigDecimal.ONE, OrderStatus.PLANNED, null, null, null);
        when(orderPort.findById(mine.id())).thenReturn(Optional.of(mine));
        when(orderPort.findById(foreign.id())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service().query(List.of(mine.id(), foreign.id()), null))
                .isInstanceOf(IllegalArgumentException.class);
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
