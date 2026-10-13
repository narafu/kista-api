package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.*;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.DcaExecutionPage;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DcaExecutionQueryServiceTest {

    StrategyPort strategyPort = mock(StrategyPort.class);
    AccountPort accountPort = mock(AccountPort.class);
    DcaExecutionPort executionPort = mock(DcaExecutionPort.class);
    OrderPort orderPort = mock(OrderPort.class);
    MarketCalendarPort calendar = mock(MarketCalendarPort.class);
    DcaExecutionQueryService service = new DcaExecutionQueryService(strategyPort, accountPort, executionPort, orderPort, calendar);

    @Test
    void joins_orders_and_us_session_dates() {
        UUID strategyId = UUID.randomUUID();
        Strategy dca = new Strategy(strategyId, UUID.randomUUID(), StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(dca);
        UUID orderId = UUID.randomUUID();
        DcaExecution ordered = DcaExecution.ordered(strategyId, LocalDate.of(2026, 9, 30), orderId, 3, null);
        DcaExecution skipped = DcaExecution.skipped(strategyId, LocalDate.of(2026, 8, 31), DcaSkipReason.CONDITION_NOT_MET, "x", null);
        when(executionPort.findPage(strategyId, 0, 20)).thenReturn(List.of(ordered, skipped));
        when(executionPort.count(strategyId)).thenReturn(2L);
        when(calendar.usSessionDateOf(any())).thenAnswer(inv -> inv.<LocalDate>getArgument(0).minusDays(1));
        Order order = new Order(orderId, dca.accountId(), UUID.randomUUID(), LocalDate.of(2026, 9, 30), StrategyTicker.UPRO,
                OrderType.MOO, OrderTiming.AT_OPEN, OrderDirection.BUY, 3, null, OrderStatus.FILLED, "E1", 3, new BigDecimal("80"));
        when(orderPort.findAllByIds(List.of(orderId))).thenReturn(List.of(order));

        DcaExecutionPage page = service.list(strategyId, UUID.randomUUID(), 0, 20);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content().get(0).order()).isEqualTo(order);
        assertThat(page.content().get(0).usSessionDate()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(page.content().get(1).order()).isNull();
    }

    @Test
    void clamps_size() {
        UUID strategyId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(new Strategy(strategyId, UUID.randomUUID(),
                StrategyType.DCA, StrategyStatus.ACTIVE, StrategyTicker.UPRO, StrategyCycleSeedType.NONE));
        when(executionPort.findPage(strategyId, 0, 100)).thenReturn(List.of());

        assertThat(service.list(strategyId, UUID.randomUUID(), -1, 999).size()).isEqualTo(100);
        verify(executionPort).findPage(strategyId, 0, 100);
    }

    @Test
    void rejects_non_dca_strategy() {
        UUID strategyId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(new Strategy(strategyId, UUID.randomUUID(),
                StrategyType.INFINITE, StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));

        assertThatThrownBy(() -> service.list(strategyId, UUID.randomUUID(), 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
