package com.kista.admin.adapter.out.internal;

import com.kista.sharedkernel.OrderStatus;
import com.kista.admin.domain.model.AdminManualTradeCorrectionCommand;
import com.kista.admin.domain.model.AdminReorderCommand;
import com.kista.admin.domain.model.AdminReorderResult;
import com.kista.admin.domain.model.AdminReorderTimingAvailability;
import com.kista.admin.domain.model.AdminBrokerCredentialException;
import com.kista.admin.domain.model.AdminBrokerRateLimitException;
import com.kista.admin.domain.model.AdminTradeCorrectionResult;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.StrategyStatus;
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
        // 테스트에서는 read/write 타임아웃 분리가 무의미하므로 동일 클라이언트를 양쪽에 주입
        adapter = new TradingCommandHttpAdapter(client, client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private static AdminReorderCommand reorderCommand() {
        return new AdminReorderCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OrderTiming.AT_CLOSE, LocalDate.of(2026, 7, 1), OrderDirection.SELL,
                2, new BigDecimal("250.00"), "memo");
    }

    private static AdminManualTradeCorrectionCommand correctionCommand() {
        return new AdminManualTradeCorrectionCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new AdminManualTradeCorrectionCommand.Fill(
                        LocalDate.of(2026, 7, 1), OrderDirection.SELL, 2,
                        new BigDecimal("267.37"), "MANUAL-1", "memo")));
    }

    // --- 정상 경로 ---

    @Test
    void reorder_요청_경로와_바디를_전송하고_응답을_역직렬화한다() throws InterruptedException {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AdminReorderCommand command = reorderCommand();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    {"userId":"%s","accountId":"%s","strategyId":"%s","sourceOrderId":"%s",
                     "originalStatus":"PLANNED","resultingStatus":"PLANNED","newOrderExternalId":null}
                    """.formatted(userId, command.accountId(), command.strategyId(), orderId))
                .build());

        AdminReorderResult result = adapter.reorder(command);

        assertThat(result.sourceOrderId()).isEqualTo(orderId);
        assertThat(result.originalStatus()).isEqualTo(OrderStatus.PLANNED);
        assertThat(result.resultingStatus()).isEqualTo(OrderStatus.PLANNED);
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).isEqualTo("/api/internal/trading/reorder");
        assertThat(recorded.getMethod()).isEqualTo("POST");
    }

    @Test
    void correctManualFills_요청_경로와_바디를_전송하고_응답을_역직렬화한다() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    {"userId":"%s","accountId":"%s","strategyId":"%s","processedCount":1,"finalHoldings":0,
                     "finalAvgPrice":266.65,"finalUsdDeposit":7200.05,"strategyStatus":"PAUSED",
                     "cycleEnded":true,"cycleEndDate":"2026-07-01"}
                    """.formatted(userId, accountId, strategyId))
                .build());

        AdminTradeCorrectionResult result = adapter.correctManualFills(correctionCommand());

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.finalHoldings()).isZero();
        assertThat(result.strategyStatus()).isEqualTo(StrategyStatus.PAUSED);
        assertThat(result.cycleEnded()).isTrue();
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).isEqualTo("/api/internal/trading/trade-corrections");
        assertThat(recorded.getMethod()).isEqualTo("POST");
    }

    @Test
    void reorderTimingAvailability_응답을_역직렬화한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    {"atOpen":true,"atClose":true,"immediate":false}
                    """)
                .build());

        AdminReorderTimingAvailability result = adapter.reorderTimingAvailability();

        assertThat(result.atOpen()).isTrue();
        assertThat(result.atClose()).isTrue();
        assertThat(result.immediate()).isFalse();
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).isEqualTo("/api/internal/trading/reorder-timing-availability");
        assertThat(recorded.getMethod()).isEqualTo("GET");
    }

    // --- 실패 모드 변환 ---
    // trading-core 쪽 GlobalExceptionHandler(전역 공유)가 매핑한 상태코드를 admin의
    // GlobalExceptionHandler가 다시 매핑할 수 있는 원래 예외 타입으로 되돌려야 한다.

    @Test
    void reorder_404_응답은_NoSuchElementException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> adapter.reorder(reorderCommand()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void reorder_400_응답은_IllegalArgumentException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(400).build());

        assertThatThrownBy(() -> adapter.reorder(reorderCommand()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // trading 쪽 GlobalExceptionHandler가 ProblemDetail.detail에 실은 실제 거절 사유가
    // 고정 문구로 뭉개지지 않고 admin 운영자에게 그대로 전달돼야 한다
    @Test
    void reorder_400_응답의_ProblemDetail_상세메시지를_그대로_전달한다() {
        server.enqueue(new MockResponse.Builder()
                .code(400).addHeader("Content-Type", "application/problem+json")
                .body("""
                    {"type":"about:blank","title":"Invalid Request","status":400,
                     "detail":"현재 시장 단계에서 IMMEDIATE 접수가 불가합니다"}
                    """)
                .build());

        assertThatThrownBy(() -> adapter.reorder(reorderCommand()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("현재 시장 단계에서 IMMEDIATE 접수가 불가합니다");
    }

    @Test
    void reorder_422_응답은_AdminBrokerCredentialException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(422).build());

        assertThatThrownBy(() -> adapter.reorder(reorderCommand()))
                .isInstanceOf(AdminBrokerCredentialException.class);
    }

    @Test
    void reorder_429_응답은_AdminBrokerRateLimitException으로_변환된다() throws InterruptedException {
        // 자동 재시도 비활성화 — 429 응답 1건만으로 즉시 매핑돼야 한다(실제 브로커 주문을
        // 유발하는 쓰기 요청을 라이브러리 기본값으로 재전송하면 안 됨 — InternalApiClientConfig 참고)
        server.enqueue(new MockResponse.Builder().code(429).build());

        assertThatThrownBy(() -> adapter.reorder(reorderCommand()))
                .isInstanceOf(AdminBrokerRateLimitException.class);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

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
