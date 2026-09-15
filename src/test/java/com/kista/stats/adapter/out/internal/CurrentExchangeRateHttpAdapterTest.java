package com.kista.stats.adapter.out.internal;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentExchangeRateHttpAdapterTest {

    private MockWebServer server;
    private CurrentExchangeRateHttpAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient client = RestClient.builder().baseUrl(server.url("/").toString()).build();
        adapter = new CurrentExchangeRateHttpAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void 응답을_BigDecimal로_역직렬화한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("1365.20")
                .build());

        BigDecimal midRate = adapter.getMidRate();

        assertThat(midRate).isEqualByComparingTo("1365.20");
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("/api/internal/trading/stats/exchange-rate");
    }

    // 네트워크 오류·5xx 등은 StatsService의 "환율 조회 실패 시 null" 계약을 지키기 위해
    // 여기서 흡수해 null을 반환한다 — 벤치마크 비교 본체까지 실패시키지 않는다.
    @Test
    void 장애_응답은_예외를_전파하지_않고_null을_반환한다() {
        server.enqueue(new MockResponse.Builder().code(500).build());

        BigDecimal midRate = adapter.getMidRate();

        assertThat(midRate).isNull();
    }
}
