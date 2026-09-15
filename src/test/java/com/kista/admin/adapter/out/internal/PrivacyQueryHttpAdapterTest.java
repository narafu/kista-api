package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.PrivacyQueryPort;
import com.kista.admin.domain.model.AdminFidaOrderCommand;
import com.kista.admin.domain.model.AdminPrivacyBaseUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyOrderUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyTradeBaseView;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyQueryHttpAdapterTest {

    private MockWebServer server;
    private PrivacyQueryHttpAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient client = RestClient.builder().baseUrl(server.url("/").toString()).build();
        adapter = new PrivacyQueryHttpAdapter(client, client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void 응답을_역직렬화하고_쿼리파라미터를_전달한다() throws InterruptedException {
        UUID id = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    [{"id":"%s","releaseDate":"2026-01-01","ticker":"SOXL","currentCycleStart":100.0,
                      "currentCycleRealizedPnl":0,"avgPrice":null,"holdings":0,"orders":[]}]
                    """.formatted(id))
                .build());

        List<AdminPrivacyTradeBaseView> result = adapter.findBasesFromTradeDate(LocalDate.of(2026, 1, 1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(id);
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("/api/internal/privacy/trade-bases");
        assertThat(recorded.getTarget()).contains("fromReleaseDate=2026-01-01");
    }

    @Test
    void createBase는_상태코드로_created를_판정하고_id로_재조회한다() throws InterruptedException {
        UUID id = UUID.randomUUID();
        // 1) POST /api/internal/fida-orders -> 201 (신규 저장)
        server.enqueue(new MockResponse.Builder()
                .code(201).addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"%s","releaseDate":"2026-01-01","ticker":"SOXL","currentCycleStart":100.0,
                     "currentCycleRealizedPnl":0,"avgPrice":null,"holdings":0,"orders":[]}
                    """.formatted(id))
                .build());
        // 2) GET /api/internal/privacy/trade-bases/{id} -> 전체 view(order id 포함) 재조회
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"%s","releaseDate":"2026-01-01","ticker":"SOXL","currentCycleStart":100.0,
                     "currentCycleRealizedPnl":0,"avgPrice":null,"holdings":0,
                     "orders":[{"id":"%s","direction":"BUY","orderType":"LOC","price":95.0,"quantity":10}]}
                    """.formatted(id, UUID.randomUUID()))
                .build());

        AdminFidaOrderCommand command = new AdminFidaOrderCommand(LocalDate.of(2026, 1, 1), StrategyTicker.SOXL,
                new BigDecimal("100.0"), BigDecimal.ZERO, null, 0,
                List.of(new AdminFidaOrderCommand.PlannedOrder(OrderDirection.BUY, OrderType.LOC, 10, new BigDecimal("95.0"))));

        PrivacyQueryPort.CreateBaseResult result = adapter.createBase(command);

        assertThat(result.created()).isTrue();
        assertThat(result.view().id()).isEqualTo(id);
        assertThat(result.view().orders()).hasSize(1);

        RecordedRequest first = server.takeRequest();
        assertThat(first.getMethod()).isEqualTo("POST");
        assertThat(first.getTarget()).contains("/api/internal/fida-orders");
        RecordedRequest second = server.takeRequest();
        assertThat(second.getMethod()).isEqualTo("GET");
        assertThat(second.getTarget()).contains("/api/internal/privacy/trade-bases/" + id);
    }

    @Test
    void createBase_200이면_created_false() {
        UUID id = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"%s","releaseDate":"2026-01-01","ticker":"SOXL","currentCycleStart":100.0,
                     "currentCycleRealizedPnl":0,"avgPrice":null,"holdings":0,"orders":[]}
                    """.formatted(id))
                .build());
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"%s","releaseDate":"2026-01-01","ticker":"SOXL","currentCycleStart":100.0,
                     "currentCycleRealizedPnl":0,"avgPrice":null,"holdings":0,"orders":[]}
                    """.formatted(id))
                .build());

        AdminFidaOrderCommand command = new AdminFidaOrderCommand(LocalDate.of(2026, 1, 1), StrategyTicker.SOXL,
                new BigDecimal("100.0"), BigDecimal.ZERO, null, 0, List.of());

        PrivacyQueryPort.CreateBaseResult result = adapter.createBase(command);

        assertThat(result.created()).isFalse();
    }

    @Test
    void createBase_409면_AdminPrivacyTradeConflictException으로_변환한다() {
        server.enqueue(new MockResponse.Builder()
                .code(409).addHeader("Content-Type", "application/json")
                .body("""
                    {"detail":"같은 날짜/종목에 내용이 다른 데이터가 존재합니다"}
                    """)
                .build());

        AdminFidaOrderCommand command = new AdminFidaOrderCommand(LocalDate.of(2026, 1, 1), StrategyTicker.SOXL,
                new BigDecimal("100.0"), BigDecimal.ZERO, null, 0, List.of());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> adapter.createBase(command)))
                .isInstanceOf(com.kista.admin.domain.model.AdminPrivacyTradeConflictException.class)
                .hasMessage("같은 날짜/종목에 내용이 다른 데이터가 존재합니다");
    }

    @Test
    void updateBase_요청을_전달하고_응답을_역직렬화한다() throws InterruptedException {
        UUID baseId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"%s","releaseDate":"2026-01-01","ticker":"SOXL","currentCycleStart":110.0,
                     "currentCycleRealizedPnl":5.0,"avgPrice":100.0,"holdings":50,"orders":[]}
                    """.formatted(baseId))
                .build());

        AdminPrivacyBaseUpdateCommand command = new AdminPrivacyBaseUpdateCommand(
                new BigDecimal("110.0"), new BigDecimal("5.0"), new BigDecimal("100.0"), 50);

        AdminPrivacyTradeBaseView result = adapter.updateBase(baseId, command);

        assertThat(result.holdings()).isEqualTo(50);
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("PATCH");
        assertThat(recorded.getTarget()).contains("/api/internal/privacy/trade-bases/" + baseId);
    }

    @Test
    void updateBase_404면_NoSuchElementException으로_변환한다() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        AdminPrivacyBaseUpdateCommand command = new AdminPrivacyBaseUpdateCommand(
                new BigDecimal("110.0"), new BigDecimal("5.0"), null, 0);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> adapter.updateBase(UUID.randomUUID(), command)))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void updateOrder_요청을_전달하고_응답을_역직렬화한다() throws InterruptedException {
        UUID baseId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"%s","releaseDate":"2026-01-01","ticker":"SOXL","currentCycleStart":100.0,
                     "currentCycleRealizedPnl":0,"avgPrice":null,"holdings":0,"orders":[]}
                    """.formatted(baseId))
                .build());

        AdminPrivacyOrderUpdateCommand command = new AdminPrivacyOrderUpdateCommand(new BigDecimal("96.0"), 20);

        AdminPrivacyTradeBaseView result = adapter.updateOrder(baseId, orderId, command);

        assertThat(result.id()).isEqualTo(baseId);
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("PATCH");
        assertThat(recorded.getTarget()).contains("/api/internal/privacy/trade-bases/" + baseId + "/orders/" + orderId);
    }
}
