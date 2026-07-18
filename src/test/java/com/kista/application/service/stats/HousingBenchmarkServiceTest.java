package com.kista.application.service.stats;

import com.kista.domain.model.stats.HousingBenchmarkPrice;
import com.kista.domain.port.out.HousingBenchmarkFeedPort;
import com.kista.domain.port.out.HousingBenchmarkPricePort;
import com.kista.domain.port.out.NotifyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HousingBenchmarkServiceTest {

    @Mock private HousingBenchmarkFeedPort feedPort;
    @Mock private HousingBenchmarkPricePort pricePort;
    @Mock private NotifyPort notifyPort;

    private HousingBenchmarkService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new HousingBenchmarkService(feedPort, pricePort, notifyPort);
    }

    @Test
    void fetchAndSave_fetchesKbLandAptQteSalePricesAndUpsertsAllRows() {
        HousingBenchmarkPrice seoul = price("서울", "1100000000");
        when(feedPort.fetchAptQteSalePrices()).thenReturn(List.of(seoul));

        service.fetchAndSave();

        ArgumentCaptor<List<HousingBenchmarkPrice>> captor = ArgumentCaptor.captor();
        verify(pricePort).upsertAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(seoul);
        verify(notifyPort, never()).notifyError(any());
    }

    @Test
    void fetchAndSave_notifiesErrorWhenKbLandFetchFails() {
        RuntimeException failure = new RuntimeException("kbland api down");
        when(feedPort.fetchAptQteSalePrices()).thenThrow(failure);

        service.fetchAndSave();

        verify(pricePort, never()).upsertAll(any());
        verify(notifyPort).notifyError(failure);
    }

    private static HousingBenchmarkPrice price(String regionName, String regionCode) {
        return new HousingBenchmarkPrice(
                "KBLAND",
                "APT_QTE_SALE_PRICE",
                regionCode,
                regionName,
                LocalDate.of(2026, 6, 1),
                new BigDecimal("52600.990329"),
                new BigDecimal("86950.460240"),
                new BigDecimal("126352.960785"),
                new BigDecimal("181363.605443"),
                new BigDecimal("344468.133292"),
                new BigDecimal("6.548700530837"),
                LocalDate.of(2026, 6, 15),
                Instant.parse("2026-06-20T00:00:00Z")
        );
    }
}
