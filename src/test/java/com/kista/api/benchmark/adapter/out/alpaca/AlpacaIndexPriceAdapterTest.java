package com.kista.api.benchmark.adapter.out.alpaca;

import com.kista.api.benchmark.domain.model.IndexPrice;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlpacaIndexPriceAdapterTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final AlpacaProperties properties = new AlpacaProperties(
            "test-key", "test-secret", "https://data.test");

    // 기본 시각: 2024-01-06 00:00Z(뉴욕 1-05 19:00) — 뉴욕 당일 봉이 이미 확정된 시점
    private AlpacaIndexPriceAdapter buildAdapter() {
        return buildAdapter(Instant.parse("2024-01-06T00:00:00Z"));
    }

    private AlpacaIndexPriceAdapter buildAdapter(Instant now) {
        return new AlpacaIndexPriceAdapter(builder.build(), properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    // t는 뉴욕 자정의 UTC 시각(겨울 EST 기준 05:00Z)
    private static String barsJson(String... dateCloses) {
        StringBuilder bars = new StringBuilder();
        for (int i = 0; i < dateCloses.length; i += 2) {
            if (!bars.isEmpty()) bars.append(',');
            bars.append("{\"t\":\"%sT05:00:00Z\",\"c\":%s}".formatted(dateCloses[i], dateCloses[i + 1]));
        }
        return "{\"bars\":[" + bars + "],\"next_page_token\":null}";
    }

    @Test
    void 일별_종가를_미국_거래일로_변환해_반환한다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlpacaIndexPriceAdapter adapter = buildAdapter();
        // t는 UTC — 2024-01-02T05:00:00Z = 뉴욕 2024-01-02 00:00 (미국 거래일 2024-01-02)
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test/v2/stocks/SPY/bars")))
                .andExpect(header("APCA-API-KEY-ID", "test-key"))
                // iex 일봉 종가는 공식(통합) 종가와 다르다 — 반드시 sip (회귀 방지)
                .andExpect(queryParam("feed", "sip"))
                .andExpect(queryParam("start", "2024-01-01"))
                // 무료 플랜은 최근 15분에 걸린 end를 주면 sip이 403이다 — end 없이 받는다
                .andExpect(request -> assertThat(request.getURI().getQuery()).doesNotContain("end="))
                .andRespond(withSuccess("""
                        {"bars":[{"t":"2024-01-02T05:00:00Z","c":470.12},
                                 {"t":"2024-01-03T05:00:00Z","c":468.55}],
                         "symbol":"SPY","next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        List<IndexPrice> result = adapter.fetchDailyCloses(
                "SPY", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result).containsExactly(
                new IndexPrice("SPY", LocalDate.of(2024, 1, 2), new BigDecimal("470.12")),
                new IndexPrice("SPY", LocalDate.of(2024, 1, 3), new BigDecimal("468.55")));
    }

    @Test
    void bars가_null이면_빈_목록을_반환한다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlpacaIndexPriceAdapter adapter = buildAdapter();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test")))
                .andRespond(withSuccess("{\"bars\":null,\"symbol\":\"SPY\"}", MediaType.APPLICATION_JSON));

        assertThat(adapter.fetchDailyCloses("SPY", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)))
                .isEmpty();
    }

    @Test
    void to_이후_봉은_버린다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlpacaIndexPriceAdapter adapter = buildAdapter();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test")))
                .andRespond(withSuccess(barsJson("2024-01-03", "468.55", "2024-01-04", "467.28", "2024-01-05", "467.92"),
                        MediaType.APPLICATION_JSON));

        assertThat(adapter.fetchDailyCloses("SPY", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 4)))
                .extracting(IndexPrice::tradeDate)
                .containsExactly(LocalDate.of(2024, 1, 3), LocalDate.of(2024, 1, 4));
    }

    @Test
    void 뉴욕_오늘_봉은_16시16분_전이면_진행_중이라_버린다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 뉴욕 2024-01-05 16:15(EST) — 정규 마감 뒤지만 통합 시세 지연 15분이 아직 안 지났다
        AlpacaIndexPriceAdapter adapter = buildAdapter(Instant.parse("2024-01-05T21:15:00Z"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test")))
                .andRespond(withSuccess(barsJson("2024-01-04", "467.28", "2024-01-05", "467.92"), MediaType.APPLICATION_JSON));

        assertThat(adapter.fetchDailyCloses("SPY", LocalDate.of(2024, 1, 4), LocalDate.of(2024, 1, 6)))
                .extracting(IndexPrice::tradeDate)
                .containsExactly(LocalDate.of(2024, 1, 4));
    }

    @Test
    void 뉴욕_오늘_봉은_16시16분부터_확정으로_받는다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlpacaIndexPriceAdapter adapter = buildAdapter(Instant.parse("2024-01-05T21:16:00Z"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test")))
                .andRespond(withSuccess(barsJson("2024-01-04", "467.28", "2024-01-05", "467.92"), MediaType.APPLICATION_JSON));

        assertThat(adapter.fetchDailyCloses("SPY", LocalDate.of(2024, 1, 4), LocalDate.of(2024, 1, 6)))
                .extracting(IndexPrice::tradeDate)
                .containsExactly(LocalDate.of(2024, 1, 4), LocalDate.of(2024, 1, 5));
    }

    @Test
    void 조기_마감일도_16시16분_전에는_오늘_봉을_버린다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 2023-11-24(추수감사절 다음날) 13:00 조기 마감, 뉴욕 14:00 — 봉은 확정이어도 보수적으로 버리고 다음 동기화(from=최대일+1)가 받는다
        AlpacaIndexPriceAdapter adapter = buildAdapter(Instant.parse("2023-11-24T19:00:00Z"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test")))
                .andRespond(withSuccess(barsJson("2023-11-22", "455.02", "2023-11-24", "455.30"), MediaType.APPLICATION_JSON));

        assertThat(adapter.fetchDailyCloses("SPY", LocalDate.of(2023, 11, 22), LocalDate.of(2023, 11, 25)))
                .extracting(IndexPrice::tradeDate)
                .containsExactly(LocalDate.of(2023, 11, 22));
    }
}
