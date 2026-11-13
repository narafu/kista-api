package com.kista.api.admin.adapter.out.internal;

import com.kista.shared.contract.trading.TradeCorrectionRequest;
import com.kista.shared.contract.trading.TradeCorrectionResponse;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyStatus;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingCommandHttpAdapterTest {

    private MockWebServer server;
    private TradingCommandHttpAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // InternalApiClientConfig와 동일하게 자동 재시도를 비활성화한 팩토리 사용 —
        // 기본 팩토리(httpclient5)는 POST 429 등을 1회 자동 재시도해 실제 운영 빈과 다른 요청 횟수를 관찰하게 됨
        var httpClient = HttpClients.custom().disableAutomaticRetries().build();
        RestClient client = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
        adapter = new TradingCommandHttpAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private static TradeCorrectionRequest correctionCommand() {
        return new TradeCorrectionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new TradeCorrectionRequest.Fill(
                        LocalDate.of(2026, 7, 1), OrderDirection.SELL, BigDecimal.valueOf(2),
                        new BigDecimal("267.37"), "MANUAL-1", "memo")));
    }

    // --- 정상 경로 ---

    @Test
    void correctManualFills_요청_경로와_바디를_전송하고_응답을_역직렬화한다() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    {"userId":"%s","accountId":"%s","strategyId":"%s","processedCount":1,"finalHoldings":0,
                     "finalAvgPrice":266.65,"finalDeposit":7200.05,"strategyStatus":"PAUSED",
                     "cycleEnded":true,"cycleEndDate":"2026-07-01"}
                    """.formatted(userId, accountId, strategyId))
                .build());

        TradeCorrectionResponse result = adapter.correctManualFills(correctionCommand());

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.finalHoldings()).isZero();
        assertThat(result.strategyStatus()).isEqualTo(StrategyStatus.PAUSED);
        assertThat(result.cycleEnded()).isTrue();
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).isEqualTo("/api/internal/trading/trade-corrections");
        assertThat(recorded.getMethod()).isEqualTo("POST");
    }

    // --- 실패 모드 변환 ---
    // trading-core 쪽 ProblemDetailExceptionHandler(전역 공유)가 매핑한 상태코드를 admin의
    // ProblemDetailExceptionHandler가 다시 매핑할 수 있는 원래 예외 타입으로 되돌려야 한다.

    @Test
    void correctManualFills_404_응답은_NoSuchElementException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> adapter.correctManualFills(correctionCommand()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void correctManualFills_400_응답은_IllegalArgumentException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(400).build());

        assertThatThrownBy(() -> adapter.correctManualFills(correctionCommand()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateStrategyStatus_요청_경로와_쿼리파라미터를_전송한다() throws InterruptedException {
        UUID accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder().code(200).build());

        adapter.updateStrategyStatus(accountId, strategyId, StrategyStatus.PAUSED);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).isEqualTo(
                "/api/internal/trading/accounts/%s/strategies/%s/status?status=PAUSED".formatted(accountId, strategyId));
        assertThat(recorded.getMethod()).isEqualTo("PATCH");
    }

    @Test
    void updateStrategyStatus_404_응답은_NoSuchElementException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> adapter.updateStrategyStatus(UUID.randomUUID(), UUID.randomUUID(), StrategyStatus.PAUSED))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void updateStrategyStatus_400_응답은_IllegalArgumentException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(400).build());

        assertThatThrownBy(() -> adapter.updateStrategyStatus(UUID.randomUUID(), UUID.randomUUID(), StrategyStatus.PAUSED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void correctManualFills_400_응답의_ProblemDetail_상세메시지를_그대로_전달한다() {
        server.enqueue(new MockResponse.Builder()
                .code(400).addHeader("Content-Type", "application/problem+json")
                .body("""
                    {"type":"about:blank","title":"Invalid Request","status":400,
                     "detail":"SELL quantity가 현재 holdings를 초과합니다"}
                    """)
                .build());

        assertThatThrownBy(() -> adapter.correctManualFills(correctionCommand()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SELL quantity가 현재 holdings를 초과합니다");
    }
}
