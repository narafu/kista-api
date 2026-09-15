package com.kista.web.trading;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveStrategyCountAdapterTest {

    private MockWebServer server;
    private ActiveStrategyCountAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        var httpClient = HttpClients.custom().disableAutomaticRetries().build();
        RestClient client = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
        adapter = new ActiveStrategyCountAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void countActiveByUserId_내부_API_응답을_그대로_반환한다() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("2")
                .build());

        long count = adapter.countActiveByUserId(userId);

        assertThat(count).isEqualTo(2);
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("/api/internal/trading/active-strategy-count");
        assertThat(recorded.getTarget()).contains("userId=" + userId);
        assertThat(recorded.getMethod()).isEqualTo("GET");
    }

    @Test
    void countActiveByUserId_응답_본문이_없으면_0을_반환한다() {
        UUID userId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder().code(204).build());

        long count = adapter.countActiveByUserId(userId);

        assertThat(count).isZero();
    }
}
