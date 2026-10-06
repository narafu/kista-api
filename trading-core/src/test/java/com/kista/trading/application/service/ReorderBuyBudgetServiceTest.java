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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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

    private void stub(Order source, String planned, String live) {
        when(orderPort.findById(source.id())).thenReturn(Optional.of(source));
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(account);
        when(orderPort.sumPlannedBuyByAccountAndDate(accountId, DATE)).thenReturn(new BigDecimal(planned));
        when(liveBalancePort.getLiveBalance(any(), any())).thenReturn(new BrokerBalance(0, BigDecimal.ZERO, new BigDecimal(live)));
    }

    @Test
    void 정상_계산_원본_FILLED면_환급_0() {
        Order src = order(OrderDirection.BUY, OrderStatus.FILLED, DATE);
        stub(src, "300", "1000");

        ReorderBuyBudget b = service().query(src.id(), null);

        assertThat(b.plannedBuy()).isEqualByComparingTo("300");
        assertThat(b.sourceRefund()).isEqualByComparingTo("0");
        assertThat(b.liveOrderable()).isEqualByComparingTo("1000");
        assertThat(b.remaining()).isEqualByComparingTo("700");
    }

    @Test
    void 원본_PLANNED_BUY면_가격x수량_환급() {
        Order src = order(OrderDirection.BUY, OrderStatus.PLANNED, DATE);
        stub(src, "300", "1000");

        ReorderBuyBudget b = service().query(src.id(), DATE);

        assertThat(b.sourceRefund()).isEqualByComparingTo("200");
        assertThat(b.remaining()).isEqualByComparingTo("900");
    }

    @Test
    void 원본_PLACED_BUY도_환급() {
        Order src = order(OrderDirection.BUY, OrderStatus.PLACED, DATE);
        stub(src, "0", "500");

        assertThat(service().query(src.id(), null).remaining()).isEqualByComparingTo("700");
    }

    @Test
    void 원본_SELL_FAILED_다른_거래일은_환급_0() {
        Order sell = order(OrderDirection.SELL, OrderStatus.PLANNED, DATE);
        stub(sell, "300", "1000");
        assertThat(service().query(sell.id(), null).sourceRefund()).isEqualByComparingTo("0");

        Order failed = order(OrderDirection.BUY, OrderStatus.FAILED, DATE);
        when(orderPort.findById(failed.id())).thenReturn(Optional.of(failed));
        assertThat(service().query(failed.id(), null).sourceRefund()).isEqualByComparingTo("0");

        // 원본 거래일(어제)과 조회 거래일(DATE)이 다르면 원본이 합계에 없으므로 환급 없음
        Order other = order(OrderDirection.BUY, OrderStatus.PLANNED, DATE.minusDays(1));
        when(orderPort.findById(other.id())).thenReturn(Optional.of(other));
        assertThat(service().query(other.id(), DATE).sourceRefund()).isEqualByComparingTo("0");
    }

    @Test
    void live_실패면_liveOrderable_remaining_null() {
        Order src = order(OrderDirection.BUY, OrderStatus.PLANNED, DATE);
        when(orderPort.findById(src.id())).thenReturn(Optional.of(src));
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(account);
        when(orderPort.sumPlannedBuyByAccountAndDate(accountId, DATE)).thenReturn(new BigDecimal("300"));
        when(liveBalancePort.getLiveBalance(any(), any())).thenThrow(new RuntimeException("timeout"));

        ReorderBuyBudget b = service().query(src.id(), null);

        assertThat(b.plannedBuy()).isEqualByComparingTo("300");
        assertThat(b.sourceRefund()).isEqualByComparingTo("200");
        assertThat(b.liveOrderable()).isNull();
        assertThat(b.remaining()).isNull();
    }

    @Test
    void 합계를_live보다_먼저_조회한다() {
        Order src = order(OrderDirection.BUY, OrderStatus.FILLED, DATE);
        stub(src, "300", "1000");

        service().query(src.id(), null);

        InOrder o = inOrder(orderPort, liveBalancePort);
        o.verify(orderPort).sumPlannedBuyByAccountAndDate(accountId, DATE);
        o.verify(liveBalancePort).getLiveBalance(any(), any());
    }

    @Test
    void 주문이_없으면_예외() {
        UUID id = UUID.randomUUID();
        when(orderPort.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().query(id, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
