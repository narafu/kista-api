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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @DisplayName("getSnapshots: days 파라미터를 findRecentDaysGlobal에 위임")
    void getSnapshots_delegates_days_to_port() {
        AccountCycleHistoryEntry entry = entry(new BigDecimal("26.00"));
        when(cycleHistoryPort.findRecentDaysGlobal(30)).thenReturn(List.of(entry));

        assertThat(sut.getSnapshots(30)).hasSize(1);
    }

    private AccountCycleHistoryEntry entry(BigDecimal currentPrice) {
        return new AccountCycleHistoryEntry(
                UUID.randomUUID(), Ticker.SOXL,
                new BigDecimal("1000.00"), currentPrice,
                new BigDecimal("25.00"), 30,
                Instant.now());
    }
}
