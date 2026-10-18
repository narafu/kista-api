package com.kista.tradingcore.trading.adapter.out.feargreed;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CryptoFearGreedIndexAdapterTest {

    private static final String URL = "https://api.alternative.me/fng/?limit=1";

    // alternative.me 응답 축약 — data[0].value가 최신 지수(문자열)
    private static final String BODY = """
            {
              "name": "Fear and Greed Index",
              "data": [
                {"value": "27", "value_classification": "Fear", "timestamp": "1792022400", "time_until_update": "35000"}
              ],
              "metadata": {"error": null}
            }
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CryptoFearGreedIndexAdapter adapter = new CryptoFearGreedIndexAdapter(builder.build());

    @Test
    void returns_latest_value_scaled_to_two_decimals() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(adapter.latest()).contains(new BigDecimal("27.00"));
        server.verify();
    }

    @Test
    void returns_empty_on_empty_data() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThat(adapter.latest()).isEmpty();
    }

    @Test
    void returns_empty_on_unparseable_value() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"data\":[{\"value\":\"abc\"}]}", MediaType.APPLICATION_JSON));

        assertThat(adapter.latest()).isEmpty();
    }

    @Test
    void returns_empty_on_missing_value() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"data\":[{}]}", MediaType.APPLICATION_JSON));

        assertThat(adapter.latest()).isEmpty();
    }

    @Test
    void returns_empty_on_http_error() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(adapter.latest()).isEmpty();
    }
}
