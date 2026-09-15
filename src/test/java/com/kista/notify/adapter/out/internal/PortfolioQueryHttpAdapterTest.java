package com.kista.notify.adapter.out.internal;

import com.kista.notify.application.port.output.PortfolioQueryPort;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;
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
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void getCurrent_내부_API_응답을_own_type으로_반환한다() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("{\"ticker\":\"TQQQ\",\"holdings\":10,\"avgPrice\":100.50,\"usdDeposit\":500.00,\"closingPrice\":105.00}")
                .build());

        PortfolioQueryPort.PortfolioCurrentView current = adapter.getCurrent(userId);

        assertThat(current.ticker()).isEqualTo(StrategyTicker.TQQQ);
        assertThat(current.holdings()).isEqualTo(10);
        assertThat(current.avgPrice()).isEqualByComparingTo("100.50");
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("/api/internal/trading/stats/portfolio/current");
        assertThat(recorded.getTarget()).contains("userId=" + userId);
    }

    @Test
    void getCurrent_404이면_NoSuchElementException으로_변환한다() {
        // trading 쪽 GlobalExceptionHandler가 ProblemDetail(RFC 7807)의 detail 필드에 실은 원 메시지를 복원해야 한다 —
        // TelegramBotService가 이 예외 타입으로 "포트폴리오 없음" 안내 메시지를 분기한다(개요 참고)
        server.enqueue(new MockResponse.Builder()
                .code(404).addHeader("Content-Type", "application/json")
                .body("{\"detail\":\"포트폴리오 데이터가 없습니다.\"}")
                .build());

        assertThatThrownBy(() -> adapter.getCurrent(UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("포트폴리오 데이터가 없습니다.");
    }

    @Test
    void getCurrent_404_응답에_detail이_없으면_fallback_문구를_사용한다() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> adapter.getCurrent(UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("포트폴리오 데이터가 없습니다.");
    }

    @Test
    void getHistory_내부_API_응답을_own_type_리스트로_반환한다() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("[{\"tradeDate\":\"2026-01-05\",\"ticker\":\"TQQQ\",\"direction\":\"BUY\",\"orderType\":\"LOC\",\"quantity\":3,\"price\":100.00}]")
                .build());

        List<PortfolioQueryPort.PortfolioOrderView> history = adapter.getHistory(userId, from, to, StrategyTicker.TQQQ);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).direction()).isEqualTo(OrderDirection.BUY);
        assertThat(history.get(0).orderType()).isEqualTo(OrderType.LOC);
        assertThat(history.get(0).quantity()).isEqualTo(3);
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("/api/internal/trading/stats/portfolio/history");
        assertThat(recorded.getTarget()).contains("userId=" + userId);
        assertThat(recorded.getTarget()).contains("ticker=TQQQ");
    }
}
