package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.CyclePerformancePageResponse;
import com.kista.adapter.in.web.dto.EquityCurveResponse;
import com.kista.adapter.in.web.dto.StatsSummaryResponse;
import com.kista.adapter.in.web.dto.HousingBenchmarkComparisonResponse;
import com.kista.adapter.in.web.dto.HousingBenchmarkRegionsResponse;
import com.kista.adapter.in.web.dto.HousingBenchmarkSeriesResponse;
import com.kista.domain.model.stats.BenchmarkAssetType;
import com.kista.domain.model.stats.BenchmarkScope;
import com.kista.domain.model.stats.EtfBenchmarkSymbol;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.UserStatsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "통계", description = "사용자 전략 수익 통계 (DB 근사 집계)")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

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
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return EquityCurveResponse.from(userStats.getEquityCurve(userId, from, to));
    }

    @Operation(summary = "사이클 성과 목록", description = "종료·진행 중 사이클의 손익/수익률/소요일 (커서 페이지네이션).")
    @GetMapping("/cycles")
    public CyclePerformancePageResponse getCycles(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) Strategy.Type type,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        return CyclePerformancePageResponse.from(
                userStats.getCyclePerformances(userId, type, cursorInstant, Math.clamp(size, 1, 200)));
    }

    @Operation(summary = "벤치마크 비교 (서울 아파트 · ETF)",
            description = "USD 투자 성과와 벤치마크(서울 아파트 분위 가격 또는 SPY/QQQ/QLD/IBIT/ETHA ETF)를 비교합니다. "
                    + "benchmarkType=ETF면 symbol이 필수이며 quintile은 무시됩니다.")
    @GetMapping("/housing-benchmark")
    public HousingBenchmarkComparisonResponse getHousingBenchmarkComparison(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "PORTFOLIO") BenchmarkScope scope,
            @RequestParam(required = false) UUID strategyId,
            @RequestParam(defaultValue = "HOUSING") BenchmarkAssetType benchmarkType,
            @RequestParam(defaultValue = "3") int quintile,
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
        if (quintile < 1 || quintile > 5) {
            throw new IllegalArgumentException("quintile은 1부터 5까지여야 합니다");
        }
        return HousingBenchmarkComparisonResponse.from(
                userStats.getHousingBenchmarkComparison(
                        userId, scope, strategyId, quintile, from, to));
    }

    @Operation(summary = "KB 지역 5분위 가격 시계열",
            description = "KB 5분위 매매평균가격 원본 시계열 (regionCode 미지정 시 서울 기본값, 투자 성과와 무관).")
    @GetMapping("/housing-benchmark/series")
    public HousingBenchmarkSeriesResponse getHousingBenchmarkSeries(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String regionCode) {
        // 서비스도 동일 검증을 하지만 여기서 fast-fail — 잘못된 파라미터로 서비스·DB 조회를 타지 않도록 의도적 중복
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to 이후일 수 없습니다");
        }
        return HousingBenchmarkSeriesResponse.from(userStats.getHousingBenchmarkSeries(from, to, regionCode));
    }

    @Operation(summary = "서울 아파트 등 KB 지역 카탈로그",
            description = "5분위 시계열 조회에 사용 가능한 지역 코드·명 목록.")
    @GetMapping("/housing-benchmark/regions")
    public HousingBenchmarkRegionsResponse getHousingBenchmarkRegions() {
        return HousingBenchmarkRegionsResponse.from(userStats.getHousingBenchmarkRegions());
    }
}
