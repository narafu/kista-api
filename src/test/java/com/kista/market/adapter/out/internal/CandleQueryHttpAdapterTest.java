package com.kista.market.adapter.out.internal;

import com.kista.market.domain.model.TossDailyCandle;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandleQueryHttpAdapterTest {

    private MockWebServer server;
    private CandleQueryHttpAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        var httpClient = HttpClients.custom().disableAutomaticRetries().build();
        RestClient client = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
        adapter = new CandleQueryHttpAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void latestDailyCandles_내부_API_응답을_own_type으로_역직렬화한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("[{\"date\":\"2026-01-02\",\"open\":100,\"high\":110,\"low\":90,\"close\":105,\"volume\":1000}]")
                .build());

        List<TossDailyCandle> candles = adapter.latestDailyCandles("QQQ", 1);

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).close()).isEqualByComparingTo("105");
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("/api/internal/broker/candles/latest");
        assertThat(recorded.getTarget()).contains("symbol=QQQ");
        assertThat(recorded.getTarget()).contains("interval=1d");
        assertThat(recorded.getTarget()).contains("count=1");
    }
}
