package com.kista.application.service;

import com.kista.domain.model.Order;
import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.out.TradeHistoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeHistoryServiceTest {

    @Mock
    TradeHistoryPort tradeHistoryPort;

    @InjectMocks
    TradeHistoryService sut;

    @Test
    void getHistory_delegates_to_port() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 28);
        TradeHistory h = new TradeHistory(UUID.randomUUID(), from, "SOXL", "SOXL_DIVISION",
                Order.OrderType.LOC, Order.OrderDirection.BUY, 10,
                new BigDecimal("25.00"), new BigDecimal("250.00"),
                Order.OrderStatus.PLACED, "KIS001", null, Instant.now());
        when(tradeHistoryPort.findBy(from, to, "SOXL")).thenReturn(List.of(h));

        List<TradeHistory> result = sut.getHistory(from, to, "SOXL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("SOXL");
    }
}
