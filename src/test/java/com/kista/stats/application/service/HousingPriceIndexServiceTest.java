package com.kista.stats.application.service;

import com.kista.stats.domain.model.HousingPriceIndex;
import com.kista.stats.application.port.output.HousingBenchmarkFeedPort;
import com.kista.stats.application.port.output.HousingPriceIndexPort;
import com.kista.stats.application.event.StatsAlertRaisedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HousingPriceIndexServiceTest {

    @Mock private HousingBenchmarkFeedPort feedPort;
    @Mock private HousingPriceIndexPort indexPort;
    @Mock private ApplicationEventPublisher eventPublisher;

    private HousingPriceIndexService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new HousingPriceIndexService(feedPort, indexPort, eventPublisher);
    }

    @Test
    void fetchAndSave_fetchesKbLandWeeklyIndexAndUpsertsAllRows() {
        HousingPriceIndex seoul = index("서울", "1100000000");
        when(feedPort.fetchWeeklyAptSalePriceIndex(2)).thenReturn(List.of(seoul));

        service.fetchAndSave(2);

        ArgumentCaptor<List<HousingPriceIndex>> captor = ArgumentCaptor.captor();
        verify(indexPort).upsertAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(seoul);
        verify(eventPublisher, never()).publishEvent(any(StatsAlertRaisedEvent.class));
    }

    @Test
    void fetchAndSave_notifiesErrorWhenKbLandFetchFails() {
        RuntimeException failure = new RuntimeException("kbland api down");
        when(feedPort.fetchWeeklyAptSalePriceIndex(20)).thenThrow(failure);

        service.fetchAndSave(20);

        verify(indexPort, never()).upsertAll(any());
        verify(eventPublisher).publishEvent(argThat(
                (StatsAlertRaisedEvent e) -> "kbland api down".equals(e.message())));
    }

    private static HousingPriceIndex index(String regionName, String regionCode) {
        return new HousingPriceIndex(
                "KBLAND",
                "WEEKLY_APT_SALE_PRICE_INDEX",
                regionCode,
                regionName,
                LocalDate.of(2026, 7, 6),
                new BigDecimal("100.000000000000"),
                LocalDate.of(2026, 8, 3),
                Instant.parse("2026-08-03T00:00:00Z")
        );
    }
}
