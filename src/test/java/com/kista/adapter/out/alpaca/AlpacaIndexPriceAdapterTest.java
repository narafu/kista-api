package com.kista.adapter.out.alpaca;

import com.kista.domain.model.stats.IndexPrice;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlpacaIndexPriceAdapterTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final AlpacaProperties properties = new AlpacaProperties(
            "https://paper-api.alpaca.markets", "test-key", "test-secret", "https://data.test");
    private final AlpacaIndexPriceAdapter adapter = new AlpacaIndexPriceAdapter(restTemplate, properties);

    @Test
    void 일별_종가를_미국_거래일로_변환해_반환한다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        // t는 UTC — 2024-01-02T05:00:00Z = 뉴욕 2024-01-02 00:00 (미국 거래일 2024-01-02)
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test/v2/stocks/SPY/bars")))
                .andExpect(header("APCA-API-KEY-ID", "test-key"))
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
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test")))
                .andRespond(withSuccess("{\"bars\":null,\"symbol\":\"SPY\"}", MediaType.APPLICATION_JSON));

        assertThat(adapter.fetchDailyCloses("SPY", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)))
                .isEmpty();
    }
}
