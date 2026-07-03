package com.kista.adapter.out.toss;

import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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

    // Toss API 응답: {"result": [...]} 래퍼 헬퍼
    private static TossPriceApi.PricesResponse wrap(TossPriceApi.PriceItem... items) {
        return new TossPriceApi.PricesResponse(List.of(items));
    }

    @Test
    @DisplayName("복수 종목 현재가 정상 파싱")
    void getPrices_multipleSymbols_success() {
        var item = new TossPriceApi.PriceItem("SOXL", "25.50", "USD");
        when(tossHttpClient.getCommon(eq("/api/v1/prices"), any(), eq(TossPriceApi.PricesResponse.class)))
            .thenReturn(wrap(item));

        Map<Ticker, BigDecimal> result = tossPriceApi.getPrices(List.of(Ticker.SOXL));

        assertThat(result).containsEntry(Ticker.SOXL, new BigDecimal("25.50"));
    }

    @Test
    @DisplayName("미등록 종목 (AAPL)은 결과에서 제외")
    void getPrices_unknownSymbolExcluded() {
        var item = new TossPriceApi.PriceItem("AAPL", "180.00", "USD");
        when(tossHttpClient.getCommon(eq("/api/v1/prices"), any(), eq(TossPriceApi.PricesResponse.class)))
            .thenReturn(wrap(item));

        Map<Ticker, BigDecimal> result = tossPriceApi.getPrices(List.of(Ticker.SOXL));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("PriceSnapshot: prevClose == current (Toss 전일종가 API 없음)")
    void getPriceSnapshot_prevCloseEqualsCurrent() {
        var item = new TossPriceApi.PriceItem("SOXL", "25.50", "USD");
        when(tossHttpClient.getCommon(any(), any(), eq(TossPriceApi.PricesResponse.class)))
            .thenReturn(wrap(item));

        PriceSnapshot snapshot = tossPriceApi.getPriceSnapshot(Ticker.SOXL);

        assertThat(snapshot.current()).isEqualByComparingTo("25.50");
        assertThat(snapshot.prevClose()).isEqualByComparingTo(snapshot.current());
    }

    @Test
    @DisplayName("null 응답 시 빈 Map 반환")
    void getPrices_nullResponse_returnsEmptyMap() {
        when(tossHttpClient.getCommon(any(), any(), eq(TossPriceApi.PricesResponse.class)))
            .thenReturn(null);

        Map<Ticker, BigDecimal> result = tossPriceApi.getPrices(List.of(Ticker.SOXL));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("복수 스냅샷: prevClose == current (Toss 전일종가 근사)")
    void getPriceSnapshots_allPrevCloseEqualCurrent() {
        when(tossHttpClient.getCommon(any(), any(), eq(TossPriceApi.PricesResponse.class)))
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
