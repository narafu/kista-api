package com.kista.market.adapter.out.internal;

import com.kista.market.application.port.output.MarketCalendarQueryPort;
import com.kista.market.domain.model.MarketSession;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketCalendarQueryHttpAdapterTest {

    private MockWebServer server;
    private MarketCalendarQueryHttpAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        var httpClient = HttpClients.custom().disableAutomaticRetries().build();
        RestClient client = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
        adapter = new MarketCalendarQueryHttpAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void findHolidaysForMonth_내부_API_응답을_그대로_반환한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("[\"2026-01-01\"]")
                .build());

        List<LocalDate> holidays = adapter.findHolidaysForMonth(2026, 1);

        assertThat(holidays).containsExactly(LocalDate.of(2026, 1, 1));
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("/api/internal/marketcalendar/holidays");
        assertThat(recorded.getTarget()).contains("year=2026");
        assertThat(recorded.getTarget()).contains("month=1");
    }

    @Test
    void isMarketOpen_true_응답을_반환한다() {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("true")
                .build());

        assertThat(adapter.isMarketOpen(LocalDate.of(2026, 1, 2))).isTrue();
    }

    @Test
    void isMarketOpen_응답_본문이_없으면_false를_반환한다() {
        server.enqueue(new MockResponse.Builder().code(204).build());

        assertThat(adapter.isMarketOpen(LocalDate.of(2026, 1, 2))).isFalse();
    }

    @Test
    void currentSession_내부_API_응답을_own_type으로_변환한다() {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("{\"session\":\"DIRECT\",\"isDst\":true}")
                .build());

        MarketCalendarQueryPort.SessionView session = adapter.currentSession();

        assertThat(session.session()).isEqualTo(MarketSession.DIRECT);
        assertThat(session.isDst()).isTrue();
    }
}
