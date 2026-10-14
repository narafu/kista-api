package com.kista.tradingcore.trading.adapter.out.feargreed;

import com.kista.shared.platform.http.CnnRestClients;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CnnFearGreedIndexAdapterTest {

    private static final String URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";

    // 2026-10-14 실측 응답 축약 — 최상위 fear_and_greed.score가 조회 시점 현재값
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
            .requestInterceptor(CnnRestClients.browserHeaderInterceptor());
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CnnFearGreedIndexAdapter adapter = new CnnFearGreedIndexAdapter(builder.build());

    @Test
    void returns_latest_score_scaled_to_two_decimals() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Referer", "https://edition.cnn.com/markets/fear-and-greed"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(adapter.latest()).contains(new BigDecimal("45.09"));
        server.verify();
    }

    @Test
    void returns_empty_on_http_error() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(adapter.latest()).isEmpty();
    }

    @Test
    void returns_empty_on_missing_score() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"fear_and_greed_historical\":{\"data\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(adapter.latest()).isEmpty();
    }

    @Test
    void returns_empty_on_malformed_json() {
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>blocked</html>", MediaType.APPLICATION_JSON));

        assertThat(adapter.latest()).isEmpty();
    }
}
