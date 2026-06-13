package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TosPriceApi 단위 테스트")
class TosPriceApiTest {

    @Mock TossHttpClient tossHttpClient;
    TosPriceApi tosPriceApi;

    // Toss 계좌: kisAppKey=clientId, kisSecretKey=clientSecret 재사용
    static final Account ACCOUNT = new Account(
        UUID.randomUUID(), UUID.randomUUID(), "테스트",
        "12345678901", "cid", "csecret", "1", Account.Broker.TOSS
    );

    @BeforeEach
    void setUp() {
        tosPriceApi = new TosPriceApi(tossHttpClient);
    }

    // Toss API 응답: {"result": [...]} 래퍼 헬퍼
    private static TosPriceApi.PricesResponse wrap(TosPriceApi.PriceItem... items) {
        return new TosPriceApi.PricesResponse(List.of(items));
    }

    @Test
    @DisplayName("복수 종목 현재가 정상 파싱")
    void getPrices_multipleSymbols_success() {
        var item = new TosPriceApi.PriceItem("SOXL", "25.50", "USD");
        when(tossHttpClient.get(eq("/api/v1/prices"), any(), any(), eq(TosPriceApi.PricesResponse.class)))
            .thenReturn(wrap(item));

        Map<Ticker, BigDecimal> result = tosPriceApi.getPrices(List.of(Ticker.SOXL), ACCOUNT);

        assertThat(result).containsEntry(Ticker.SOXL, new BigDecimal("25.50"));
    }

    @Test
    @DisplayName("미등록 종목 (AAPL)은 결과에서 제외")
    void getPrices_unknownSymbolExcluded() {
        var item = new TosPriceApi.PriceItem("AAPL", "180.00", "USD");
        when(tossHttpClient.get(eq("/api/v1/prices"), any(), any(), eq(TosPriceApi.PricesResponse.class)))
            .thenReturn(wrap(item));

        Map<Ticker, BigDecimal> result = tosPriceApi.getPrices(List.of(Ticker.SOXL), ACCOUNT);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("PriceSnapshot: prevClose == current (Toss 전일종가 API 없음)")
    void getPriceSnapshot_prevCloseEqualsCurrent() {
        var item = new TosPriceApi.PriceItem("SOXL", "25.50", "USD");
        when(tossHttpClient.get(any(), any(), any(), eq(TosPriceApi.PricesResponse.class)))
            .thenReturn(wrap(item));

        PriceSnapshot snapshot = tosPriceApi.getPriceSnapshot(Ticker.SOXL, ACCOUNT);

        assertThat(snapshot.current()).isEqualByComparingTo("25.50");
        assertThat(snapshot.prevClose()).isEqualByComparingTo(snapshot.current());
    }

    @Test
    @DisplayName("null 응답 시 빈 Map 반환")
    void getPrices_nullResponse_returnsEmptyMap() {
        when(tossHttpClient.get(any(), any(), any(), eq(TosPriceApi.PricesResponse.class)))
            .thenReturn(null);

        Map<Ticker, BigDecimal> result = tosPriceApi.getPrices(List.of(Ticker.SOXL), ACCOUNT);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("복수 스냅샷: prevClose == current (Toss 전일종가 근사)")
    void getPriceSnapshots_allPrevCloseEqualCurrent() {
        when(tossHttpClient.get(any(), any(), any(), eq(TosPriceApi.PricesResponse.class)))
            .thenReturn(wrap(
                new TosPriceApi.PriceItem("SOXL", "25.50", "USD"),
                new TosPriceApi.PriceItem("TQQQ", "50.00", "USD")
            ));

        Map<Ticker, PriceSnapshot> snapshots = tosPriceApi.getPriceSnapshots(
            List.of(Ticker.SOXL, Ticker.TQQQ), ACCOUNT);

        assertThat(snapshots).hasSize(2);
        snapshots.forEach((ticker, snap) ->
            assertThat(snap.prevClose()).isEqualByComparingTo(snap.current()));
    }

    @Test
    @DisplayName("빈 tickers 목록 요청 시 HTTP 미호출 후 빈 Map 반환")
    void getPrices_emptyTickers_returnsEmptyMapWithoutHttpCall() {
        Map<Ticker, BigDecimal> result = tosPriceApi.getPrices(List.of(), ACCOUNT);

        assertThat(result).isEmpty();
    }
}
