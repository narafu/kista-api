package com.kista.application.service;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.out.TradingCycleHistoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock TradingCycleHistoryPort cycleHistoryPort;

    @InjectMocks PortfolioService sut;

    @Test
    @DisplayName("getCurrent: 가장 최근 이력 1건 반환")
    void getCurrent_returns_latest_entry() {
        AccountCycleHistoryEntry entry = entry(new BigDecimal("26.00"));
        when(cycleHistoryPort.findRecentGlobal(1)).thenReturn(List.of(entry));

        assertThat(sut.getCurrent()).isEqualTo(entry);
    }

    @Test
    @DisplayName("getCurrent: 이력 없으면 NoSuchElementException")
    void getCurrent_throws_when_no_history() {
        when(cycleHistoryPort.findRecentGlobal(1)).thenReturn(List.of());

        assertThatThrownBy(() -> sut.getCurrent())
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("포트폴리오");
    }

    @Test
    @DisplayName("getSnapshots: from/to 파라미터를 findBetween에 위임")
    void getSnapshots_delegates_date_range_to_port() {
        AccountCycleHistoryEntry entry = entry(new BigDecimal("26.00"));
        when(cycleHistoryPort.findBetween(any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(entry));

        assertThat(sut.getSnapshots(LocalDate.now().minusDays(30), LocalDate.now())).hasSize(1);
    }

    private AccountCycleHistoryEntry entry(BigDecimal currentPrice) {
        return new AccountCycleHistoryEntry(
                UUID.randomUUID(), Ticker.SOXL,
                new BigDecimal("1000.00"), currentPrice,
                new BigDecimal("25.00"), 30,
                Instant.now());
    }
}
