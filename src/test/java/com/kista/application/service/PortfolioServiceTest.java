package com.kista.application.service;

import com.kista.domain.model.PortfolioSnapshot;
import com.kista.domain.port.out.PortfolioSnapshotPort;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock
    PortfolioSnapshotPort portfolioSnapshotPort;

    @InjectMocks
    PortfolioService sut;

    @Test
    void getCurrent_returns_latest_snapshot() {
        PortfolioSnapshot snap = snapshot(LocalDate.now());
        when(portfolioSnapshotPort.findRecent(7)).thenReturn(List.of(snap));

        assertThat(sut.getCurrent()).isEqualTo(snap);
    }

    @Test
    void getCurrent_throws_when_no_snapshot_in_last_7_days() {
        when(portfolioSnapshotPort.findRecent(7)).thenReturn(List.of());

        assertThatThrownBy(() -> sut.getCurrent())
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("포트폴리오");
    }

    @Test
    void getSnapshots_delegates_days_to_port() {
        PortfolioSnapshot snap = snapshot(LocalDate.now());
        when(portfolioSnapshotPort.findRecent(30)).thenReturn(List.of(snap));

        assertThat(sut.getSnapshots(30)).hasSize(1);
    }

    private PortfolioSnapshot snapshot(LocalDate date) {
        return new PortfolioSnapshot(UUID.randomUUID(), date, "SOXL", 100,
                new BigDecimal("25.0000"), new BigDecimal("26.0000"),
                new BigDecimal("2600.00"), new BigDecimal("1000.00"),
                new BigDecimal("3600.00"), null, Instant.now());
    }
}
