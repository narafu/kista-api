package com.kista.application.service.stats;

import com.kista.domain.model.stats.IndexPrice;
import com.kista.domain.port.out.IndexPriceFeedPort;
import com.kista.domain.port.out.IndexPricePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketIndexPriceSyncServiceTest {

    @Mock private IndexPricePort indexPricePort;
    @Mock private IndexPriceFeedPort indexPriceFeedPort;

    private MarketIndexPriceSyncService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new MarketIndexPriceSyncService(indexPricePort, indexPriceFeedPort);
    }

    @Test
    void syncAndSave_emptyTable_fetchesFromEarliestIndexDate() {
        when(indexPricePort.findMaxTradeDate(anyString())).thenReturn(Optional.empty());
        when(indexPriceFeedPort.fetchDailyCloses(anyString(), any(), any())).thenReturn(List.of());

        service.syncAndSave();

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(indexPriceFeedPort, times(4)).fetchDailyCloses(anyString(), fromCaptor.capture(), any());
        assertThat(fromCaptor.getAllValues()).allMatch(d -> d.equals(LocalDate.of(2000, 1, 1)));
    }

    @Test
    void syncAndSave_existingMaxTradeDate_fetchesFromNextDay() {
        LocalDate maxDate = LocalDate.of(2026, 7, 10);
        when(indexPricePort.findMaxTradeDate(anyString())).thenReturn(Optional.of(maxDate));
        when(indexPriceFeedPort.fetchDailyCloses(anyString(), any(), any())).thenReturn(List.of());

        service.syncAndSave();

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(indexPriceFeedPort, times(4)).fetchDailyCloses(anyString(), fromCaptor.capture(), any());
        assertThat(fromCaptor.getAllValues()).allMatch(d -> d.equals(maxDate.plusDays(1)));
    }

    @Test
    void syncAndSave_alreadyUpToDate_doesNotCallFeed() {
        LocalDate today = LocalDate.now(com.kista.common.TimeZones.KST);
        when(indexPricePort.findMaxTradeDate(anyString())).thenReturn(Optional.of(today.plusDays(1)));

        service.syncAndSave();

        verify(indexPriceFeedPort, never()).fetchDailyCloses(anyString(), any(), any());
        verify(indexPricePort, never()).saveAll(any());
    }

    @Test
    void syncAndSave_oneSymbolFails_stillSavesOthersThenThrows() {
        when(indexPricePort.findMaxTradeDate(anyString())).thenReturn(Optional.empty());
        when(indexPriceFeedPort.fetchDailyCloses(eq("SPY"), any(), any()))
                .thenThrow(new RuntimeException("alpaca down"));
        IndexPrice qqqPrice = new IndexPrice("QQQ", LocalDate.of(2024, 1, 2), new BigDecimal("400.00"));
        when(indexPriceFeedPort.fetchDailyCloses(eq("QQQ"), any(), any())).thenReturn(List.of(qqqPrice));
        when(indexPriceFeedPort.fetchDailyCloses(eq("QLD"), any(), any())).thenReturn(List.of());
        when(indexPriceFeedPort.fetchDailyCloses(eq("IBIT"), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.syncAndSave())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPY");

        verify(indexPricePort).saveAll(List.of(qqqPrice));
        verify(indexPricePort, never()).saveAll(List.of());
    }
}
