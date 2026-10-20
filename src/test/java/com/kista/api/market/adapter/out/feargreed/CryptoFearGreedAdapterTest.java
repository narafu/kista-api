package com.kista.api.market.adapter.out.feargreed;

import com.kista.api.market.domain.model.FearGreedRating;
import com.kista.api.market.domain.model.FearGreedSnapshot;
import com.kista.shared.platform.http.CnnRestClients;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CryptoFearGreedAdapterTest {

    @Test
    void fetchHistory_parses_latest_first_response_into_ascending_snapshots() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor(CnnRestClients.browserHeaderInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CryptoFearGreedAdapter adapter = new CryptoFearGreedAdapter(builder.build());

        server.expect(requestTo("https://api.alternative.me/fng/?limit=2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"))
                .andExpect(header("Accept", "application/json, text/plain, */*"))
                .andExpect(header("Accept-Language", "en-US,en;q=0.9"))
                .andExpect(header("Referer", "https://edition.cnn.com/markets/fear-and-greed"))
                .andExpect(header("Origin", "https://edition.cnn.com"))
                .andExpect(request -> assertThat(request.getHeaders().containsHeader("Accept-Encoding")).isFalse())
                .andRespond(withSuccess("""
                        {
                          "data": [
                            {
                              "value": "15",
                              "value_classification": "Extreme Fear",
                              "timestamp": "1782777600",
                              "time_until_update": "3600"
                            },
                            {
                              "value": "52",
                              "value_classification": "Neutral",
                              "timestamp": "1782691200"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = adapter.fetchHistory(2);

        // 최신순 응답을 오름차순으로 뒤집고, timestamp(UTC 자정 Unix 초)를 그대로 Instant로 쓴다
        assertThat(result).extracting(FearGreedSnapshot::snapshotDate)
                .containsExactly(Instant.ofEpochSecond(1782691200L), Instant.ofEpochSecond(1782777600L));
        assertThat(result).extracting(FearGreedSnapshot::source).containsOnly("CRYPTO");
        assertThat(result.getLast().value()).isEqualTo(15);
        assertThat(result.getLast().rating()).isEqualTo(FearGreedRating.EXTREME_FEAR);
        assertThat(result.getFirst().rating()).isEqualTo(FearGreedRating.NEUTRAL);
        server.verify();
    }
}
