package com.kista.application.service;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.out.OrderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeHistoryServiceTest {

    @Mock
    OrderPort orderPort;

    @InjectMocks
    TradeHistoryService sut;

    @Test
    void getHistory_delegates_to_port() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 28);
        Order o = new Order(UUID.randomUUID(), UUID.randomUUID(), from, Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 10,
                new BigDecimal("25.00"), Order.OrderStatus.PLACED, "KIS001");
        when(orderPort.findBy(from, to, Ticker.SOXL)).thenReturn(List.of(o));

        List<Order> result = sut.getHistory(from, to, Ticker.SOXL);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ticker()).isEqualTo(Ticker.SOXL);
    }
}
