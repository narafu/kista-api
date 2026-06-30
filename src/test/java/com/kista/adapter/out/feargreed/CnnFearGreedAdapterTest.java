package com.kista.adapter.out.feargreed;

import com.kista.domain.model.market.FearGreedRating;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CnnFearGreedAdapterTest {

    @Test
    void fetch_parses_plain_json_response_without_accept_encoding_header() {
        RestTemplate restTemplate = new FearGreedConfig().fearGreedRestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        CnnFearGreedAdapter adapter = new CnnFearGreedAdapter(restTemplate);

        server.expect(requestTo("https://production.dataviz.cnn.io/index/fearandgreed/graphdata"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"))
                .andExpect(header("Accept", "application/json, text/plain, */*"))
                .andExpect(header("Accept-Language", "en-US,en;q=0.9"))
                .andExpect(header("Referer", "https://edition.cnn.com/markets/fear-and-greed"))
                .andExpect(header("Origin", "https://edition.cnn.com"))
                .andExpect(request -> assertThat(request.getHeaders().containsKey("Accept-Encoding")).isFalse())
                .andRespond(withSuccess("""
                        {
                          "fear_and_greed": {
                            "score": 64.2,
                            "rating": "Greed"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = adapter.fetch();

        assertThat(result.value()).isEqualTo(64);
        assertThat(result.rating()).isEqualTo(FearGreedRating.GREED);
        server.verify();
    }
}
