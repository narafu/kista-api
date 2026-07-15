package com.kista.adapter.out.toss;

import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossCandle;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.ParameterizedTypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TossPriceApi 단위 테스트")
class TossPriceApiTest {

    @Mock TossHttpClient tossHttpClient;
    @Mock TossCandleApi tossCandleApi; // TossPriceApi 생성자 주입 의존성 (TossCandlePort 삭제됨)
    TossPriceApi tossPriceApi;

    @BeforeEach
    void setUp() {
        tossPriceApi = new TossPriceApi(tossHttpClient, tossCandleApi);
    }

    // Toss API 응답: TossResult<List<PriceItem>> 래퍼 헬퍼
    private static TossResult<List<TossPriceApi.PriceItem>> wrap(TossPriceApi.PriceItem... items) {
        return new TossResult<>(List.of(items));
    }

    @Test
    @DisplayName("복수 종목 현재가 정상 파싱")
    void getPrices_multipleSymbols_success() {
        var item = new TossPriceApi.PriceItem("SOXL", "25.50", "USD");
        when(tossHttpClient.getCommon(eq("/api/v1/prices"), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap(item));

        Map<Ticker, BigDecimal> result = tossPriceApi.getPrices(List.of(Ticker.SOXL));

        assertThat(result).containsEntry(Ticker.SOXL, new BigDecimal("25.50"));
    }

    @Test
    @DisplayName("미등록 종목 (AAPL)은 결과에서 제외")
    void getPrices_unknownSymbolExcluded() {
        var item = new TossPriceApi.PriceItem("AAPL", "180.00", "USD");
        when(tossHttpClient.getCommon(eq("/api/v1/prices"), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap(item));

        Map<Ticker, BigDecimal> result = tossPriceApi.getPrices(List.of(Ticker.SOXL));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("PriceSnapshot: prevClose == current (Toss 전일종가 API 없음)")
    void getPriceSnapshot_prevCloseEqualsCurrent() {
        var item = new TossPriceApi.PriceItem("SOXL", "25.50", "USD");
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap(item));

        PriceSnapshot snapshot = tossPriceApi.getPriceSnapshot(Ticker.SOXL);

        assertThat(snapshot.current()).isEqualByComparingTo("25.50");
        assertThat(snapshot.prevClose()).isEqualByComparingTo(snapshot.current());
    }

    @Test
    @DisplayName("같은 종목·같은 날짜 재조회 시 캔들 API 1회만 호출 (캐시 히트)")
    void getPriceSnapshot_sameSymbolSameDay_callsCandleApiOnce() {
        var item = new TossPriceApi.PriceItem("SOXL", "25.50", "USD");
        when(tossHttpClient.getCommon(eq("/api/v1/prices"), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(wrap(item));
        TossCandle candle = new TossCandle(LocalDate.now().minusDays(1), new BigDecimal("23.00"),
                new BigDecimal("23.50"), new BigDecimal("22.50"), new BigDecimal("23.20"), 900L);
        when(tossCandleApi.getCandleBefore(eq("SOXL"), eq("1d"), any())).thenReturn(Optional.of(candle));

        tossPriceApi.getPriceSnapshot(Ticker.SOXL);
        tossPriceApi.getPriceSnapshot(Ticker.SOXL);

        verify(tossCandleApi, times(1)).getCandleBefore(eq("SOXL"), eq("1d"), any());
    }

    @Test
    @DisplayName("확정 종가 캔들이 있으면 그대로 prevClose로 사용")
    void fetchPrevCloseUncached_returnsCandleClose() {
        TossCandle candle = new TossCandle(LocalDate.of(2026, 7, 13), new BigDecimal("90.00"),
                new BigDecimal("91.00"), new BigDecimal("88.50"), new BigDecimal("89.20"), 1200L);
        when(tossCandleApi.getCandleBefore(eq("SOXL"), eq("1d"), any())).thenReturn(Optional.of(candle));

        Optional<BigDecimal> result = tossPriceApi.fetchPrevCloseUncached("SOXL", Instant.now());

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("89.20");
    }

    @Test
    @DisplayName("캔들이 없으면 empty 반환 (호출부에서 current로 fallback)")
    void fetchPrevCloseUncached_noCandle_returnsEmpty() {
        when(tossCandleApi.getCandleBefore(eq("SOXL"), eq("1d"), any())).thenReturn(Optional.empty());

        Optional<BigDecimal> result = tossPriceApi.fetchPrevCloseUncached("SOXL", Instant.now());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null 응답 시 빈 Map 반환")
    void getPrices_nullResponse_returnsEmptyMap() {
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(null);

        Map<Ticker, BigDecimal> result = tossPriceApi.getPrices(List.of(Ticker.SOXL));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("복수 스냅샷: prevClose == current (Toss 전일종가 근사)")
    void getPriceSnapshots_allPrevCloseEqualCurrent() {
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap(
                new TossPriceApi.PriceItem("SOXL", "25.50", "USD"),
                new TossPriceApi.PriceItem("TQQQ", "50.00", "USD")
            ));

        Map<Ticker, PriceSnapshot> snapshots = tossPriceApi.getPriceSnapshots(List.of(Ticker.SOXL, Ticker.TQQQ));

        assertThat(snapshots).hasSize(2);
        snapshots.forEach((ticker, snap) ->
            assertThat(snap.prevClose()).isEqualByComparingTo(snap.current()));
    }

    @Test
    @DisplayName("빈 tickers 목록 요청 시 HTTP 미호출 후 빈 Map 반환")
    void getPrices_emptyTickers_returnsEmptyMapWithoutHttpCall() {
        Map<Ticker, BigDecimal> result = tossPriceApi.getPrices(List.of());

        assertThat(result).isEmpty();
    }
}
