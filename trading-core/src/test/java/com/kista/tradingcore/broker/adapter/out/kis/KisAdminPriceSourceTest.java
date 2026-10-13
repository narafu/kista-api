package com.kista.tradingcore.broker.adapter.out.kis;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("KisAdminPriceSource")
class KisAdminPriceSourceTest {

    KisPriceApi priceApi = mock(KisPriceApi.class);

    private static KisPriceApi.MultiPriceResponse.Output2 quote(String symb, String last, String base) {
        return new KisPriceApi.MultiPriceResponse.Output2(symb, last, base);
    }

    @Test
    @DisplayName("앱키 또는 시크릿이 비면 비활성")
    void blankKeyDisables() {
        assertThat(new KisAdminPriceSource(priceApi, "", "secret").enabled()).isFalse();
        assertThat(new KisAdminPriceSource(priceApi, "key", " ").enabled()).isFalse();
        assertThat(new KisAdminPriceSource(priceApi, "key", "secret").enabled()).isTrue();
    }

    @Test
    @DisplayName("last가 빈값인 종목은 현재가에서 제외 (base로 대체 금지)")
    void blankLastExcluded() {
        when(priceApi.fetchQuotes(any(), any(BrokerAccountRef.class))).thenReturn(Map.of(
                StrategyTicker.SOXL, quote("SOXL", "", "20.00"),
                StrategyTicker.TQQQ, quote("TQQQ", "30.50", "29.00")));
        var source = new KisAdminPriceSource(priceApi, "key", "secret");

        var result = source.getPrices(List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ));

        assertThat(result).containsOnlyKeys(StrategyTicker.TQQQ);
        assertThat(result.get(StrategyTicker.TQQQ)).isEqualByComparingTo(new BigDecimal("30.50"));
    }

    @Test
    @DisplayName("base가 빈값인 종목은 전일종가에서 제외")
    void blankBaseExcluded() {
        when(priceApi.fetchQuotes(any(), any(BrokerAccountRef.class))).thenReturn(Map.of(
                StrategyTicker.SOXL, quote("SOXL", "21.00", ""),
                StrategyTicker.TQQQ, quote("TQQQ", "30.50", "29.00")));
        var source = new KisAdminPriceSource(priceApi, "key", "secret");

        var result = source.getPrevCloses(List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ));

        assertThat(result).containsOnlyKeys(StrategyTicker.TQQQ);
        assertThat(result.get(StrategyTicker.TQQQ)).isEqualByComparingTo(new BigDecimal("29.00"));
    }

    @Test
    @DisplayName("비숫자·0 값은 현재가·전일종가에서 누락 처리")
    void zeroOrNonNumericExcluded() {
        when(priceApi.fetchQuotes(any(), any(BrokerAccountRef.class))).thenReturn(Map.of(
                StrategyTicker.SOXL, quote("SOXL", "abc", "0"),
                StrategyTicker.TQQQ, quote("TQQQ", "30.50", "29.00")));
        var source = new KisAdminPriceSource(priceApi, "key", "secret");
        var both = List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ);

        assertThat(source.getPrices(both)).containsOnlyKeys(StrategyTicker.TQQQ);
        assertThat(source.getPrevCloses(both)).containsOnlyKeys(StrategyTicker.TQQQ);
    }

    @Test
    @DisplayName("getClosingPrices: 확정 종가 실패·0 종목은 미포함 (라이브가 대체 없음)")
    void closingPricesExcludeFailures() {
        LocalDate d = LocalDate.of(2026, 10, 14);
        when(priceApi.fetchConfirmedClose(eq(StrategyTicker.SOXL), eq(d), any(BrokerAccountRef.class))).thenReturn(Optional.empty());
        when(priceApi.fetchConfirmedClose(eq(StrategyTicker.TQQQ), eq(d), any(BrokerAccountRef.class))).thenReturn(Optional.of(BigDecimal.ZERO));
        when(priceApi.fetchConfirmedClose(eq(StrategyTicker.UPRO), eq(d), any(BrokerAccountRef.class))).thenReturn(Optional.of(new BigDecimal("12.34")));
        var source = new KisAdminPriceSource(priceApi, "key", "secret");

        var result = source.getClosingPrices(List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ, StrategyTicker.UPRO), d);

        assertThat(result).containsOnlyKeys(StrategyTicker.UPRO);
        verify(priceApi, never()).getPrice(any(), any());
    }
}
