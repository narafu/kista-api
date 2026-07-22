package com.kista.adapter.in.web;

import com.kista.domain.model.stats.*;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.UserStatsUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StatsController.class)
@Execution(ExecutionMode.SAME_THREAD)
class StatsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort; // GlobalExceptionHandler 의존성
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean UserStatsUseCase userStats;

    private static final UUID USER_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());
    }

    @Test
    void summary를_반환한다() throws Exception {
        when(userStats.getSummary(USER_ID)).thenReturn(new StatsSummary(
                new BigDecimal("50.00"), new BigDecimal("10.00"), new BigDecimal("1000.00"),
                List.of(new StrategyTypeStats(Strategy.Type.INFINITE, 2, 1,
                        new BigDecimal("0.5000"), new BigDecimal("0.0250"), new BigDecimal("20.0"),
                        new BigDecimal("50.00"), new BigDecimal("10.00")))));

        mockMvc.perform(get("/api/stats/summary").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRealizedPnl").value(50.00))
                .andExpect(jsonPath("$.byType[0].type").value("INFINITE"))
                .andExpect(jsonPath("$.byType[0].winRate").value(0.5));
    }

    @Test
    void equity_curve를_반환한다() throws Exception {
        when(userStats.getEquityCurve(eq(USER_ID), isNull(), any(), any()))
                .thenReturn(new EquityCurve(
                        List.of(new EquityPoint(LocalDate.parse("2026-06-02"),
                                new BigDecimal("1000.00"), new BigDecimal("900.00")))));

        mockMvc.perform(get("/api/stats/equity-curve")
                        .param("from", "2026-06-01").param("to", "2026-06-30")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].date").value("2026-06-02"))
                .andExpect(jsonPath("$.points[0].totalAsset").value(1000.00))
                .andExpect(jsonPath("$.benchmark").doesNotExist());
    }

    @Test
    void equity_curve는_type_필터를_전달한다() throws Exception {
        when(userStats.getEquityCurve(eq(USER_ID), eq(Strategy.Type.VR), any(), any()))
                .thenReturn(new EquityCurve(List.of()));

        mockMvc.perform(get("/api/stats/equity-curve")
                        .param("type", "VR")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30")
                        .with(authentication(auth())))
                .andExpect(status().isOk());

        verify(userStats).getEquityCurve(
                USER_ID, Strategy.Type.VR,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
    }

    @Test
    void cycles를_커서와_함께_반환한다() throws Exception {
        var createdAt = java.time.Instant.parse("2026-02-01T00:00:00Z");
        when(userStats.getCyclePerformances(eq(USER_ID), isNull(), isNull(), eq(50)))
                .thenReturn(new CyclePerformancePage(
                        List.of(new CyclePerformance(UUID.randomUUID(), Strategy.Type.INFINITE,
                                Strategy.Ticker.SOXL, LocalDate.parse("2026-01-01"),
                                LocalDate.parse("2026-01-31"), new BigDecimal("1000.00"),
                                new BigDecimal("1100.00"), new BigDecimal("100.00"),
                                new BigDecimal("0.1000"), 30, true, createdAt)),
                        createdAt, true));

        mockMvc.perform(get("/api/stats/cycles").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].closed").value(true))
                .andExpect(jsonPath("$.nextCursor").value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void 서울_아파트_벤치마크_비교를_기본값과_통화_기준으로_반환한다() throws Exception {
        when(userStats.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, null, null))
                .thenReturn(comparison(new CurrentExchangeRate(
                        new BigDecimal("1365.20"), Instant.parse("2026-07-19T01:30:00Z"), "TOSS_INVEST")));

        mockMvc.perform(get("/api/stats/housing-benchmark").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("PORTFOLIO"))
                .andExpect(jsonPath("$.benchmark.regionCode").value("1100000000"))
                .andExpect(jsonPath("$.benchmark.quintile").value(3))
                .andExpect(jsonPath("$.period.pointCount").value(2))
                .andExpect(jsonPath("$.points[0].investmentIndexUsd").value(100.0))
                .andExpect(jsonPath("$.points[1].benchmarkIndex").value(110.0))
                .andExpect(jsonPath("$.points[1].investmentPeriodReturn").value(0.1))
                .andExpect(jsonPath("$.summary.investmentCumulativeReturn").value(0.1))
                .andExpect(jsonPath("$.summary.excessReturn").value(0.0))
                .andExpect(jsonPath("$.quality.method").value("ESTIMATED_TIME_WEIGHTED_RETURN"))
                .andExpect(jsonPath("$.quality.investmentCurrency").value("USD"))
                .andExpect(jsonPath("$.quality.benchmarkCurrency").value("KRW"))
                .andExpect(jsonPath("$.quality.notice").value(
                        "전략 운용 기록 기반 근사치입니다. 투자 성과는 USD, 서울 아파트는 KRW 현지 통화 기준이며 현재 환율은 성과 계산에 반영하지 않습니다."))
                .andExpect(jsonPath("$.currentExchangeRate.midRate").value(1365.2))
                .andExpect(jsonPath("$.currentExchangeRate.fetchedAt").value("2026-07-19T01:30:00Z"))
                .andExpect(jsonPath("$.currentExchangeRate.source").value("TOSS_INVEST"))
                .andExpect(jsonPath("$.emptyReason").doesNotExist());
    }

    @Test
    void 현재_환율이_없어도_명시적_null과_함께_200을_반환한다() throws Exception {
        when(userStats.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, null, null))
                .thenReturn(comparison(null));

        mockMvc.perform(get("/api/stats/housing-benchmark").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].investmentIndexUsd").value(100.0))
                .andExpect(jsonPath("$.currentExchangeRate").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 전략_scope는_strategyId가_필수다() throws Exception {
        mockMvc.perform(get("/api/stats/housing-benchmark")
                        .param("scope", "STRATEGY")
                        .with(authentication(auth())))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userStats);
    }

    @Test
    void 분위는_1부터_5까지만_허용한다() throws Exception {
        mockMvc.perform(get("/api/stats/housing-benchmark")
                        .param("quintile", "0")
                        .with(authentication(auth())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/stats/housing-benchmark")
                        .param("quintile", "6")
                        .with(authentication(auth())))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userStats);
    }

    @Test
    void 소유_전략과_기간_파라미터를_서비스에_전달한다() throws Exception {
        UUID strategyId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2021, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 1);
        when(userStats.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, strategyId, 5, from, to))
                .thenReturn(comparison(null));

        mockMvc.perform(get("/api/stats/housing-benchmark")
                        .param("scope", "STRATEGY")
                        .param("strategyId", strategyId.toString())
                        .param("quintile", "5")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .with(authentication(auth())))
                .andExpect(status().isOk());

        verify(userStats).getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, strategyId, 5, from, to);
    }

    @Test
    void ETF_벤치마크_비교를_반환한다() throws Exception {
        when(userStats.getEtfBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, EtfBenchmarkSymbol.QLD, null, null))
                .thenReturn(etfComparison());

        mockMvc.perform(get("/api/stats/housing-benchmark")
                        .param("benchmarkType", "ETF")
                        .param("symbol", "QLD")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.benchmark.assetType").value("ETF"))
                .andExpect(jsonPath("$.benchmark.symbol").value("QLD"))
                .andExpect(jsonPath("$.benchmark.regionCode").doesNotExist())
                .andExpect(jsonPath("$.quality.benchmarkCurrency").value("USD"));
    }

    @Test
    void ETF_벤치마크_비교는_symbol이_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/stats/housing-benchmark")
                        .param("benchmarkType", "ETF")
                        .with(authentication(auth())))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userStats);
    }

    @Test
    void benchmarkType_생략시_기존_HOUSING_동작을_그대로_따른다() throws Exception {
        when(userStats.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, null, null))
                .thenReturn(comparison(null));

        mockMvc.perform(get("/api/stats/housing-benchmark").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.benchmark.assetType").value("HOUSING"));

        verify(userStats).getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, null, null);
    }

    private static HousingBenchmarkComparison etfComparison() {
        return new HousingBenchmarkComparison(
                BenchmarkScope.PORTFOLIO,
                null,
                new HousingBenchmarkComparison.Benchmark(
                        BenchmarkAssetType.ETF, null, null, null, "QLD",
                        "QLD (ProShares Ultra QQQ (2x 레버리지))", LocalDate.of(2026, 7, 18)),
                new HousingBenchmarkComparison.Period(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), 2),
                new PerformanceComparisonSummary(
                        new BigDecimal("0.1000000000"), new BigDecimal("0.1000000000"),
                        BigDecimal.ZERO, new BigDecimal("2.1384283767"),
                        new BigDecimal("2.1384283767"), BigDecimal.ZERO, BigDecimal.ZERO),
                List.of(
                        new HousingBenchmarkPoint(
                                LocalDate.of(2026, 1, 1), new BigDecimal("100.0000000000"),
                                new BigDecimal("100.0000000000"), null, null),
                        new HousingBenchmarkPoint(
                                LocalDate.of(2026, 2, 1), new BigDecimal("110.0000000000"),
                                new BigDecimal("110.0000000000"), new BigDecimal("0.1000000000"),
                                new BigDecimal("0.1000000000"))),
                null,
                null);
    }

    @Test
    void 아파트_5분위_시계열을_반환한다() throws Exception {
        when(userStats.getHousingBenchmarkSeries(null, null, null)).thenReturn(List.of(
                new HousingBenchmarkPrice(
                        HousingBenchmarkPrice.SOURCE_KBLAND, HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE,
                        "1100000000", "서울", LocalDate.of(2026, 1, 1),
                        new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("40"),
                        new BigDecimal("80"), new BigDecimal("160"), new BigDecimal("6.5"),
                        LocalDate.of(2026, 2, 15), Instant.parse("2026-02-16T00:00:00Z"))));

        mockMvc.perform(get("/api/stats/housing-benchmark/series").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].baseMonth").value("2026-01-01"))
                .andExpect(jsonPath("$.points[0].firstQuintilePrice").value(10))
                .andExpect(jsonPath("$.points[0].fifthQuintilePrice").value(160))
                .andExpect(jsonPath("$.sourceUpdatedDate").value("2026-02-15"));
    }

    @Test
    void 시계열_역전된_기간은_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/stats/housing-benchmark/series")
                        .param("from", "2026-07-01").param("to", "2026-01-01")
                        .with(authentication(auth())))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userStats);
    }

    @Test
    void 시계열_조회는_regionCode_파라미터를_서비스에_전달한다() throws Exception {
        when(userStats.getHousingBenchmarkSeries(null, null, "0000000000")).thenReturn(List.of());

        mockMvc.perform(get("/api/stats/housing-benchmark/series")
                        .param("regionCode", "0000000000")
                        .with(authentication(auth())))
                .andExpect(status().isOk());

        verify(userStats).getHousingBenchmarkSeries(null, null, "0000000000");
    }

    @Test
    void 지역_카탈로그를_반환한다() throws Exception {
        when(userStats.getHousingBenchmarkRegions()).thenReturn(List.of(
                new HousingBenchmarkRegion("1100000000", "서울"),
                new HousingBenchmarkRegion("2600000000", "부산")));

        mockMvc.perform(get("/api/stats/housing-benchmark/regions").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].code").value("1100000000"))
                .andExpect(jsonPath("$.regions[0].name").value("서울"))
                .andExpect(jsonPath("$.regions[1].code").value("2600000000"))
                .andExpect(jsonPath("$.regions[1].name").value("부산"));
    }

    private static HousingBenchmarkComparison comparison(CurrentExchangeRate currentExchangeRate) {
        return new HousingBenchmarkComparison(
                BenchmarkScope.PORTFOLIO,
                null,
                new HousingBenchmarkComparison.Benchmark(
                        BenchmarkAssetType.HOUSING, "1100000000", "서울", 3, null,
                        "서울 아파트 3분위", LocalDate.of(2026, 6, 15)),
                new HousingBenchmarkComparison.Period(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), 2),
                new PerformanceComparisonSummary(
                        new BigDecimal("0.1000000000"), new BigDecimal("0.1000000000"),
                        BigDecimal.ZERO, new BigDecimal("2.1384283767"),
                        new BigDecimal("2.1384283767"), BigDecimal.ZERO, BigDecimal.ZERO),
                List.of(
                        new HousingBenchmarkPoint(
                                LocalDate.of(2026, 1, 1), new BigDecimal("100.0000000000"),
                                new BigDecimal("100.0000000000"), null, null),
                        new HousingBenchmarkPoint(
                                LocalDate.of(2026, 2, 1), new BigDecimal("110.0000000000"),
                                new BigDecimal("110.0000000000"), new BigDecimal("0.1000000000"),
                                new BigDecimal("0.1000000000"))),
                currentExchangeRate,
                null);
    }
}
