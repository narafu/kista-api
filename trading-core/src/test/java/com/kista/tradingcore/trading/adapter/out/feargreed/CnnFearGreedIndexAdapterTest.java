package com.kista.tradingcore.trading.adapter.out.feargreed;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CnnFearGreedIndexAdapterTest {

    private static final String URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";

    // 2026-10-14 실측 응답 축약 — 일별 점은 세션일 00:00 UTC, 마지막은 장중 실시간 점(19:34 UTC)
    private static final String BODY = """
            {
              "fear_and_greed": {"score": 45.0857142857143, "rating": "neutral"},
              "fear_and_greed_historical": {
                "score": 45.0857142857143,
                "data": [
                  {"x": 1791763200000.0, "y": 46.3428571428571, "rating": "neutral"},
                  {"x": 1791849600000.0, "y": 45.1714285714286, "rating": "neutral"},
                  {"x": 1791920042000.0, "y": 45.0857142857143, "rating": "neutral"}
                ]
              }
            }
            """;

    private final RestClient.Builder builder = RestClient.builder()
            .requestInterceptor(TradingFearGreedConfig.fearGreedHeaderInterceptor());
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CnnFearGreedIndexAdapter adapter = new CnnFearGreedIndexAdapter(builder.build());

    @Test
    void returns_daily_point_of_requested_utc_date_scaled_to_two_decimals() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Referer", "https://edition.cnn.com/markets/fear-and-greed"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 12))).contains(new BigDecimal("46.34"));
        server.verify();
    }

    @Test
    void ignores_intraday_live_point_on_same_utc_date() {
        // 10-13 일별 점(45.17)을 써야 하고 같은 UTC 날짜의 장중 점(45.09)은 무시
        server.expect(requestTo(URL)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 13))).contains(new BigDecimal("45.17"));
    }

    @Test
    void returns_empty_when_requested_date_missing() {
        server.expect(requestTo(URL)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 14))).isEmpty();
    }

    @Test
    void returns_empty_on_http_error() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 13))).isEmpty();
    }

    @Test
    void returns_empty_on_missing_historical() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"fear_and_greed\":{\"score\":45.0}}", MediaType.APPLICATION_JSON));

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 13))).isEmpty();
    }

    @Test
    void returns_empty_on_malformed_json() {
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>blocked</html>", MediaType.APPLICATION_JSON));

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 13))).isEmpty();
    }
}
