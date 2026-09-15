package com.kista.stats.adapter.out.internal;

import com.kista.stats.application.port.output.InvestmentPointsPort;
import com.kista.stats.domain.model.BenchmarkScope;
import com.kista.stats.domain.model.StrategyRef;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentPointsHttpAdapterTest {

    private MockWebServer server;
    private InvestmentPointsHttpAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient client = RestClient.builder().baseUrl(server.url("/").toString()).build();
        adapter = new InvestmentPointsHttpAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void 응답을_역직렬화한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"points":[],"effectiveFrom":"2026-01-01","effectiveTo":"2026-09-01","selectedStrategy":null}
                    """)
                .build());

        InvestmentPointsPort.Result result = adapter.fetch(
                UUID.randomUUID(), BenchmarkScope.PORTFOLIO, null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1),
                com.kista.stats.domain.model.BenchmarkGranularity.MONTHLY);

        assertThat(result.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.effectiveTo()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(result.points()).isEmpty();
        assertThat(result.selectedStrategy()).isNull();
    }

    @Test
    void STRATEGY_scope_응답의_selectedStrategy를_역직렬화한다() {
        UUID strategyId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"points":[{"baseDate":"2026-01-05","investmentIndexUsd":100.1234567890,"periodReturn":0.0012345678}],
                     "effectiveFrom":"2026-01-05","effectiveTo":"2026-02-23",
                     "selectedStrategy":{"id":"%s","accountId":"%s","type":"INFINITE","status":"ACTIVE",
                     "ticker":"SOXL","cycleSeedType":"NONE"}}
                    """.formatted(strategyId, accountId))
                .build());

        InvestmentPointsPort.Result result = adapter.fetch(
                UUID.randomUUID(), BenchmarkScope.STRATEGY, strategyId,
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23),
                com.kista.stats.domain.model.BenchmarkGranularity.WEEKLY);

        StrategyRef expected = new StrategyRef(strategyId,
                com.kista.sharedkernel.StrategyType.INFINITE, com.kista.sharedkernel.StrategyTicker.SOXL);
        assertThat(result.selectedStrategy()).isEqualTo(expected);
        assertThat(result.points()).hasSize(1);
        // MonthlyReturnCalculator.SCALE=10 정밀도가 HTTP 왕복(직렬화→역직렬화)에서 보존되는지 확인
        // — 벤치마크 비교 화면(HousingBenchmarkComparisonResponse)이 이 값을 그대로 노출한다
        var point = result.points().getFirst();
        assertThat(point.investmentIndexUsd()).isEqualByComparingTo(new java.math.BigDecimal("100.1234567890"));
        assertThat(point.investmentIndexUsd().scale()).isEqualTo(10);
        assertThat(point.periodReturn()).isEqualByComparingTo(new java.math.BigDecimal("0.0012345678"));
        assertThat(point.periodReturn().scale()).isEqualTo(10);
    }

    @Test
    void 요청_경로와_쿼리파라미터를_올바르게_구성한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"points":[],"effectiveFrom":"2026-01-01","effectiveTo":"2026-09-01","selectedStrategy":null}
                    """)
                .build());
        UUID userId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();

        adapter.fetch(userId, BenchmarkScope.STRATEGY, strategyId,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1),
                com.kista.stats.domain.model.BenchmarkGranularity.WEEKLY);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("/api/internal/trading/stats/investment-points");
        assertThat(recorded.getTarget()).contains("userId=" + userId);
        assertThat(recorded.getTarget()).contains("scope=STRATEGY");
        assertThat(recorded.getTarget()).contains("strategyId=" + strategyId);
        assertThat(recorded.getTarget()).contains("granularity=WEEKLY");
    }

    // trading 쪽 GlobalExceptionHandler가 SecurityException/NoSuchElementException/
    // IllegalArgumentException을 403/404/400으로 매핑한다 — 어댑터가 이를 원래 예외 타입으로
    // 되돌리지 못하면 api의 GlobalExceptionHandler가 매핑하지 못하는 HttpClientErrorException으로
    // 흘러 500(catch-all)이 된다.
    @Test
    void _403_응답은_SecurityException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(403).build());

        assertThatThrownBy(() -> adapter.fetch(
                UUID.randomUUID(), BenchmarkScope.STRATEGY, UUID.randomUUID(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1),
                com.kista.stats.domain.model.BenchmarkGranularity.MONTHLY))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void _404_응답은_NoSuchElementException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> adapter.fetch(
                UUID.randomUUID(), BenchmarkScope.STRATEGY, UUID.randomUUID(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1),
                com.kista.stats.domain.model.BenchmarkGranularity.MONTHLY))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void _400_응답은_IllegalArgumentException으로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(400).build());

        assertThatThrownBy(() -> adapter.fetch(
                UUID.randomUUID(), BenchmarkScope.PORTFOLIO, null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1),
                com.kista.stats.domain.model.BenchmarkGranularity.MONTHLY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
