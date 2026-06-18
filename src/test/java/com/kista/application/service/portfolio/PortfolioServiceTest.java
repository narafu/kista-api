package com.kista.application.service.portfolio;

import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.CyclePositionPort;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock CyclePositionPort cycleHistoryPort;
    @Mock OrderPort orderPort;

    @InjectMocks PortfolioService sut;

    static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("getCurrent: 가장 최근 이력 1건 반환")
    void getCurrent_returns_latest_entry() {
        CyclePositionHistoryEntry entry = entry(new BigDecimal("26.00"));
        when(cycleHistoryPort.findRecentByUser(USER_ID, 1)).thenReturn(List.of(entry));

        assertThat(sut.getCurrent(USER_ID)).isEqualTo(entry);
    }

    @Test
    @DisplayName("getCurrent: 이력 없으면 NoSuchElementException")
    void getCurrent_throws_when_no_history() {
        when(cycleHistoryPort.findRecentByUser(USER_ID, 1)).thenReturn(List.of());

        assertThatThrownBy(() -> sut.getCurrent(USER_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("포트폴리오");
    }

    @Test
    @DisplayName("getSnapshots: from/to 파라미터를 findBetween에 위임")
    void getSnapshots_delegates_date_range_to_port() {
        CyclePositionHistoryEntry entry = entry(new BigDecimal("26.00"));
        when(cycleHistoryPort.findBetweenByUser(eq(USER_ID), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(entry));

        assertThat(sut.getSnapshots(USER_ID, LocalDate.now().minusDays(30), LocalDate.now())).hasSize(1);
    }

    private CyclePositionHistoryEntry entry(BigDecimal currentPrice) {
        return new CyclePositionHistoryEntry(
                UUID.randomUUID(), Ticker.SOXL,
                new BigDecimal("1000.00"), currentPrice,
                new BigDecimal("25.00"), 30,
                Instant.now());
    }
}
