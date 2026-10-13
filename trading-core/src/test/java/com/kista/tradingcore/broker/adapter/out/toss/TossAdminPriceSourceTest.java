package com.kista.tradingcore.broker.adapter.out.toss;

import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TossAdminPriceSource")
class TossAdminPriceSourceTest {

    TossPriceApi priceApi = mock(TossPriceApi.class);
    TossAdminPriceSource source = new TossAdminPriceSource(priceApi);
    List<StrategyTicker> both = List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ);

    @Test
    @DisplayName("getPrevCloses: 캔들 실패 종목은 현재가 대체 없이 미포함")
    void prevClosesExcludeCandleFailure() {
        when(priceApi.fetchPrevCloseCached("SOXL")).thenReturn(Optional.empty());
        when(priceApi.fetchPrevCloseCached("TQQQ")).thenReturn(Optional.of(new BigDecimal("29")));

        assertThat(source.getPrevCloses(both)).containsOnlyKeys(StrategyTicker.TQQQ);
    }

    @Test
    @DisplayName("getClosingPrices: 확정 종가 실패 종목은 미포함")
    void closingPricesExcludeFailure() {
        LocalDate d = LocalDate.of(2026, 10, 14);
        when(priceApi.fetchConfirmedClose(StrategyTicker.SOXL, d)).thenReturn(Optional.of(new BigDecimal("21")));
        when(priceApi.fetchConfirmedClose(StrategyTicker.TQQQ, d)).thenReturn(Optional.empty());

        assertThat(source.getClosingPrices(both, d)).containsOnlyKeys(StrategyTicker.SOXL);
    }

    @Test
    @DisplayName("getPrices: 부분 결과를 그대로 전달")
    void pricesPartialPassThrough() {
        when(priceApi.fetchPricesPartial(both)).thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("21")));

        assertThat(source.getPrices(both)).containsOnlyKeys(StrategyTicker.SOXL);
    }
}
