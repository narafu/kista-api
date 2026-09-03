package com.kista.stats.adapter.in.web;

import com.kista.stats.adapter.in.web.dto.CyclePerformancePageResponse;
import com.kista.stats.adapter.in.web.dto.EquityCurveResponse;
import com.kista.stats.adapter.in.web.dto.StatsSummaryResponse;
import com.kista.stats.adapter.in.web.dto.HousingBenchmarkComparisonResponse;
import com.kista.stats.adapter.in.web.dto.HousingBenchmarkRegionsResponse;
import com.kista.stats.adapter.in.web.dto.HousingBenchmarkSeriesResponse;
import com.kista.stats.adapter.in.web.dto.HousingPriceIndexSeriesResponse;
import com.kista.stats.adapter.in.web.dto.EtfPriceSeriesResponse;
import com.kista.stats.domain.model.BenchmarkAssetType;
import com.kista.stats.domain.model.BenchmarkScope;
import com.kista.stats.domain.model.EtfBenchmarkSymbol;
import com.kista.domain.model.strategy.Strategy;
import com.kista.stats.application.usecase.UserStatsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.kista.sharedkernel.StrategyType;

@Tag(name = "통계", description = "사용자 전략 수익 통계 (DB 근사 집계)")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private static final CacheControl HOUSING_BENCHMARK_CACHE = CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic();

    private final UserStatsUseCase userStats;

    @Operation(summary = "수익 통계 요약", description = "실현·미실현 손익과 전략 타입별 사이클 성과 집계.")
    @GetMapping("/summary")
    public StatsSummaryResponse getSummary(@AuthenticationPrincipal UUID userId) {
        return StatsSummaryResponse.from(userStats.getSummary(userId));
    }

    @Operation(summary = "누적 자산 곡선", description = "일별 전략 운용 자산·원금.")
    @GetMapping("/equity-curve")
    public EquityCurveResponse getEquityCurve(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) StrategyType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return EquityCurveResponse.from(userStats.getEquityCurve(userId, type, from, to));
    }

    @Operation(summary = "사이클 성과 목록", description = "종료·진행 중 사이클의 손익/수익률/소요일 (커서 페이지네이션).")
    @GetMapping("/cycles")
    public CyclePerformancePageResponse getCycles(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) StrategyType type,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        return CyclePerformancePageResponse.from(
                userStats.getCyclePerformances(userId, type, cursorInstant, Math.clamp(size, 1, 200)));
    }

    @Operation(summary = "벤치마크 비교 (아파트 매매가격지수 · ETF)",
            description = "USD 투자 성과와 벤치마크(아파트 지역 매매가격지수 또는 SPY/QQQ/QLD/IBIT/ETHA ETF)를 비교합니다. "
                    + "benchmarkType=ETF면 symbol이 필수이며 regionCode는 무시됩니다.")
    @GetMapping("/housing-benchmark")
    public HousingBenchmarkComparisonResponse getHousingBenchmarkComparison(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "PORTFOLIO") BenchmarkScope scope,
            @RequestParam(required = false) UUID strategyId,
            @RequestParam(defaultValue = "HOUSING") BenchmarkAssetType benchmarkType,
            @RequestParam(defaultValue = "1100000000") String regionCode,
            @RequestParam(required = false) EtfBenchmarkSymbol symbol,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // 서비스도 동일 검증을 하지만 여기서 fast-fail — 잘못된 파라미터로 서비스·DB 조회를 타지 않도록 의도적 중복
        if (scope == BenchmarkScope.STRATEGY && strategyId == null) {
            throw new IllegalArgumentException("STRATEGY scope에는 strategyId가 필요합니다");
        }
        if (scope == BenchmarkScope.PORTFOLIO && strategyId != null) {
            throw new IllegalArgumentException("PORTFOLIO scope에는 strategyId를 지정할 수 없습니다");
        }
        if (benchmarkType == BenchmarkAssetType.ETF) {
            if (symbol == null) {
                throw new IllegalArgumentException("benchmarkType=ETF에는 symbol이 필요합니다");
            }
            return HousingBenchmarkComparisonResponse.from(
                    userStats.getEtfBenchmarkComparison(userId, scope, strategyId, symbol, from, to));
        }
        if (regionCode == null || regionCode.isBlank()) {
            throw new IllegalArgumentException("regionCode는 비어있을 수 없습니다");
        }
        return HousingBenchmarkComparisonResponse.from(
                userStats.getHousingBenchmarkComparison(
                        userId, scope, strategyId, regionCode, from, to));
    }

    @Operation(summary = "KB 지역 5분위 가격 시계열",
            description = "KB 5분위 매매평균가격 원본 시계열 (regionCode 미지정 시 서울 기본값, 투자 성과와 무관).")
    @GetMapping("/housing-benchmark/series")
    public ResponseEntity<HousingBenchmarkSeriesResponse> getHousingBenchmarkSeries(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String regionCode) {
        // 서비스도 동일 검증을 하지만 여기서 fast-fail — 잘못된 파라미터로 서비스·DB 조회를 타지 않도록 의도적 중복
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to 이후일 수 없습니다");
        }
        HousingBenchmarkSeriesResponse response = HousingBenchmarkSeriesResponse.from(userStats.getHousingBenchmarkSeries(from, to, regionCode));
        return ResponseEntity.ok().cacheControl(HOUSING_BENCHMARK_CACHE).body(response);
    }

    @Operation(summary = "아파트 주간 매매가격지수 시계열",
            description = "KB Land 주간 아파트 매매가격지수 원본 시계열 (regionCode 미지정 시 서울 기본값, 투자 성과와 무관·비교 없음).")
    @GetMapping("/housing-benchmark/index-series")
    public ResponseEntity<HousingPriceIndexSeriesResponse> getHousingPriceIndexSeries(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String regionCode) {
        // 서비스도 동일 검증을 하지만 여기서 fast-fail — 잘못된 파라미터로 서비스·DB 조회를 타지 않도록 의도적 중복
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to 이후일 수 없습니다");
        }
        HousingPriceIndexSeriesResponse response = HousingPriceIndexSeriesResponse.from(userStats.getHousingPriceIndexSeries(from, to, regionCode));
        return ResponseEntity.ok().cacheControl(HOUSING_BENCHMARK_CACHE).body(response);
    }

    @Operation(summary = "ETF 원본 종가 시계열",
            description = "ETF(SPY/QQQ/QLD/IBIT/ETHA) 일별 종가 원본 시계열 (symbol 필수, 투자 성과와 무관·비교 없음).")
    @GetMapping("/housing-benchmark/etf-series")
    public ResponseEntity<EtfPriceSeriesResponse> getEtfPriceSeries(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) EtfBenchmarkSymbol symbol) {
        // 서비스도 동일 검증을 하지만 여기서 fast-fail — 잘못된 파라미터로 서비스·DB 조회를 타지 않도록 의도적 중복
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to 이후일 수 없습니다");
        }
        if (symbol == null) {
            throw new IllegalArgumentException("symbol이 필요합니다");
        }
        EtfPriceSeriesResponse response = EtfPriceSeriesResponse.from(userStats.getEtfPriceSeries(from, to, symbol));
        return ResponseEntity.ok().cacheControl(HOUSING_BENCHMARK_CACHE).body(response);
    }

    @Operation(summary = "KB 지역 카탈로그",
            description = "아파트 벤치마크 비교(regionCode)에 사용 가능한 지역 코드·명 목록. "
                    + "5분위 시계열(`/housing-benchmark/series`)의 14개 지역은 이 목록(25개)의 부분집합이다.")
    @GetMapping("/housing-benchmark/regions")
    public ResponseEntity<HousingBenchmarkRegionsResponse> getHousingBenchmarkRegions() {
        HousingBenchmarkRegionsResponse response = HousingBenchmarkRegionsResponse.from(userStats.getHousingBenchmarkRegions());
        return ResponseEntity.ok().cacheControl(HOUSING_BENCHMARK_CACHE).body(response);
    }
}
