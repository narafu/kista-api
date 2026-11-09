package com.kista.api.admin.adapter.out.internal;

import com.kista.shared.contract.stats.PortfolioCurrentResponse;
import com.kista.shared.contract.stats.PortfolioOrderResponse;
import com.kista.api.admin.application.port.output.PortfolioQueryPort;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioQueryHttpAdapterTest {

    private MockWebServer server;
    private PortfolioQueryHttpAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient client = RestClient.builder().baseUrl(server.url("/").toString()).build();
        adapter = new PortfolioQueryHttpAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void getCurrent_내부_API_목록_응답을_own_type으로_반환한다() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("[{\"strategyType\":\"VR\",\"ticker\":\"TQQQ\",\"currency\":\"USD\",\"holdings\":10,\"avgPrice\":100.50,\"deposit\":500.00,\"closingPrice\":105.00},"
                        + "{\"strategyType\":\"DCA\",\"ticker\":\"ETH\",\"currency\":\"USDT\",\"holdings\":0.5,\"avgPrice\":2900,\"deposit\":0,\"closingPrice\":3000}]")
                .build());

        List<PortfolioCurrentResponse> current = adapter.getCurrent(userId);

        assertThat(current).hasSize(2);
        assertThat(current.getFirst().ticker()).isEqualTo(StrategyTicker.TQQQ);
        assertThat(current.getFirst().currency()).isEqualTo(Currency.USD);
        assertThat(current.getFirst().holdings()).isEqualByComparingTo("10");
        assertThat(current.getLast().currency()).isEqualTo(Currency.USDT);
        assertThat(current.getLast().holdings()).isEqualByComparingTo("0.5");
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("/api/internal/trading/stats/portfolio/current");
        assertThat(recorded.getTarget()).contains("userId=" + userId);
    }

    @Test
    void getCurrent_빈_목록_응답을_반환한다() {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body("[]").build());

        assertThat(adapter.getCurrent(UUID.randomUUID())).isEmpty();
    }

    @Test
    void getHistory_내부_API_응답을_own_type_리스트로_반환한다() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("[{\"tradeDate\":\"2026-01-05\",\"ticker\":\"TQQQ\",\"direction\":\"BUY\",\"orderType\":\"LOC\",\"quantity\":3,\"price\":100.00,\"currency\":\"USD\"}]")
                .build());

        List<PortfolioOrderResponse> history = adapter.getHistory(userId, from, to);

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().direction()).isEqualTo(OrderDirection.BUY);
        assertThat(history.getFirst().orderType()).isEqualTo(OrderType.LOC);
        assertThat(history.getFirst().quantity()).isEqualByComparingTo("3");
        assertThat(history.getFirst().currency()).isEqualTo(Currency.USD);
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("/api/internal/trading/stats/portfolio/history");
        assertThat(recorded.getTarget()).contains("userId=" + userId);
        assertThat(recorded.getTarget()).doesNotContain("ticker=");
    }
}
