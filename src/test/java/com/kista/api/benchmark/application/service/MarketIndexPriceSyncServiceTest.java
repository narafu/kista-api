package com.kista.api.benchmark.application.service;

import com.kista.shared.support.FixedClockTestConfig;
import com.kista.api.benchmark.domain.model.IndexPrice;
import com.kista.api.benchmark.domain.model.SplitAdjustment;
import com.kista.api.benchmark.application.port.output.IndexPriceFeedPort;
import com.kista.api.benchmark.application.port.output.IndexPricePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
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
        service = new MarketIndexPriceSyncService(indexPricePort, indexPriceFeedPort, FixedClockTestConfig.FIXED);
    }

    @Test
    void syncAndSave_emptyTable_fetchesFromEarliestIndexDate() {
        when(indexPricePort.findMaxTradeDate(anyString())).thenReturn(Optional.empty());
        when(indexPriceFeedPort.fetchDailyCloses(anyString(), any(), any())).thenReturn(List.of());

        service.syncAndSave();

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(indexPriceFeedPort, times(5)).fetchDailyCloses(anyString(), fromCaptor.capture(), any());
        assertThat(fromCaptor.getAllValues()).allMatch(d -> d.equals(LocalDate.of(2000, 1, 1)));
    }

    @Test
    void syncAndSave_existingMaxTradeDate_fetchesFromOverlapWindow() {
        LocalDate maxDate = LocalDate.of(2026, 1, 2);
        when(indexPricePort.findMaxTradeDate(anyString())).thenReturn(Optional.of(maxDate));
        when(indexPriceFeedPort.fetchDailyCloses(anyString(), any(), any())).thenReturn(List.of());

        service.syncAndSave();

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(indexPriceFeedPort, times(5)).fetchDailyCloses(anyString(), fromCaptor.capture(), any());
        assertThat(fromCaptor.getAllValues()).allMatch(d -> d.equals(maxDate.minusDays(10)));
    }

    @Test
    void syncAndSave_alreadyUpToDate_doesNotCallFeed() {
        LocalDate today = LocalDate.now(FixedClockTestConfig.FIXED);
        when(indexPricePort.findMaxTradeDate(anyString())).thenReturn(Optional.of(today.plusDays(11)));

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

    private static final LocalDate D1 = LocalDate.of(2025, 9, 30);
    private static final LocalDate D2 = LocalDate.of(2025, 10, 1);
    private static final LocalDate D3 = LocalDate.of(2025, 10, 2);

    private static IndexPrice etha(LocalDate date, String close) {
        return new IndexPrice("ETHA", date, new BigDecimal(close));
    }

    @Test
    void detectSplit_겹친_날이_모두_분할_전이면_마지막_날까지_조정한다() {
        // ETHA 2026-10-05 1:3 역분할과 같은 꼴 — 저장값은 분할 전 기준, 새로 받은 값은 3배
        assertThat(MarketIndexPriceSyncService.detectSplit(
                List.of(etha(D1, "60.36"), etha(D2, "61.11"), etha(D3, "60.33")),
                List.of(etha(D1, "20.12"), etha(D2, "20.37"), etha(D3, "20.11"))))
                .contains(new SplitAdjustment(D3, 3, 1));
    }

    @Test
    void detectSplit_분할일이_겹친_구간_안이면_분할_전_날까지만_조정한다() {
        assertThat(MarketIndexPriceSyncService.detectSplit(
                List.of(etha(D1, "60.36"), etha(D2, "61.11"), etha(D3, "61.29")),
                List.of(etha(D1, "20.12"), etha(D2, "20.37"), etha(D3, "61.29"))))
                .contains(new SplitAdjustment(D2, 3, 1));
    }

    @Test
    void detectSplit_분할비가_한_날만_나오면_이상값으로_보고_건너뛴다() {
        assertThat(MarketIndexPriceSyncService.detectSplit(
                List.of(etha(D1, "60.36"), etha(D2, "61.11"), etha(D3, "61.29")),
                List.of(etha(D1, "20.12"), etha(D2, "61.11"), etha(D3, "61.29"))))
                .isEmpty();
    }

    @Test
    void detectSplit_n대1_꼴이_아닌_비율과_극단값은_분할로_보지_않는다() {
        // 1.3(13:10)·0.7은 분할 꼴이 아니고, 0.01 저장값은 비율이 수천 배라 범위 밖이다
        assertThat(MarketIndexPriceSyncService.detectSplit(
                List.of(etha(D1, "26.00"), etha(D2, "26.00")), List.of(etha(D1, "20.00"), etha(D2, "20.00")))).isEmpty();
        assertThat(MarketIndexPriceSyncService.detectSplit(
                List.of(etha(D1, "14.00"), etha(D2, "14.00")), List.of(etha(D1, "20.00"), etha(D2, "20.00")))).isEmpty();
        assertThat(MarketIndexPriceSyncService.detectSplit(
                List.of(etha(D1, "61.29"), etha(D2, "61.29")), List.of(etha(D1, "0.01"), etha(D2, "0.01")))).isEmpty();
    }

    @Test
    void detectSplit_정분할은_1보다_작은_비율로_조정한다() {
        assertThat(MarketIndexPriceSyncService.detectSplit(
                List.of(etha(D1, "50.10"), etha(D2, "50.00")), List.of(etha(D1, "100.20"), etha(D2, "100.00"))))
                .contains(new SplitAdjustment(D2, 1, 2));
    }

    @Test
    void detectSplit_iex_시세_오차는_분할로_보지_않는다() {
        // QLD iex 63.62/69.09 대 공식 63.52/69.07, 최대 관측 오차 1.9%
        assertThat(MarketIndexPriceSyncService.detectSplit(
                List.of(etha(D1, "63.52"), etha(D2, "69.07"), etha(D3, "29.70")),
                List.of(etha(D1, "63.62"), etha(D2, "69.09"), etha(D3, "30.23"))))
                .isEmpty();
    }

    @Test
    void detectSplit_새_기준_뒤에_분할비가_다시_나오면_판단하지_않는다() {
        assertThat(MarketIndexPriceSyncService.detectSplit(
                List.of(etha(D1, "61.29"), etha(D2, "61.11")),
                List.of(etha(D1, "61.29"), etha(D2, "20.37"))))
                .isEmpty();
    }

    @Test
    void detectSplit_날마다_분할비가_다르면_판단하지_않는다() {
        assertThat(MarketIndexPriceSyncService.detectSplit(
                List.of(etha(D1, "60.36"), etha(D2, "40.74")),
                List.of(etha(D1, "20.12"), etha(D2, "20.37"))))
                .isEmpty();
    }

    @Test
    void syncAndSave_분할을_감지하면_조정한_뒤_덮어쓴다() {
        when(indexPricePort.findMaxTradeDate(anyString())).thenReturn(Optional.empty());
        when(indexPricePort.findMaxTradeDate("ETHA")).thenReturn(Optional.of(D3));
        when(indexPriceFeedPort.fetchDailyCloses(anyString(), any(), any())).thenReturn(List.of());
        List<IndexPrice> fetched = List.of(etha(D1, "60.36"), etha(D2, "61.11"), etha(D3, "60.33"));
        when(indexPriceFeedPort.fetchDailyCloses(eq("ETHA"), eq(D3.minusDays(10)), any())).thenReturn(fetched);
        when(indexPricePort.findBySymbolAndRange("ETHA", D3.minusDays(10), D3))
                .thenReturn(List.of(etha(D1, "20.12"), etha(D2, "20.37"), etha(D3, "20.11")));

        service.syncAndSave();

        InOrder order = inOrder(indexPricePort);
        order.verify(indexPricePort).applySplit("ETHA", new SplitAdjustment(D3, 3, 1));
        order.verify(indexPricePort).saveAll(fetched);
    }
}
