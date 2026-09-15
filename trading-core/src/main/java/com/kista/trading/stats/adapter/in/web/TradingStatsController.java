package com.kista.trading.stats.adapter.in.web;

import com.kista.trading.stats.application.usecase.TradingStatsUseCase;
import com.kista.trading.stats.adapter.in.web.dto.CyclePerformancePageResponse;
import com.kista.trading.stats.adapter.in.web.dto.EquityCurveResponse;
import com.kista.trading.stats.adapter.in.web.dto.StatsSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.kista.sharedkernel.StrategyType;

// 사용자 통계 중 trading 소유 부분(실현·미실현 손익 요약/누적 자산 곡선/사이클 성과 목록) —
// housing/ETF 벤치마크 비교는 api의 StatsController(com.kista.stats)가 계속 소유한다.
// 같은 "/api/stats" prefix를 두 컨트롤러가 sub-path로 나눠 갖는다(경로 충돌 없음).
@Tag(name = "통계", description = "사용자 전략 수익 통계 (DB 근사 집계)")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
class TradingStatsController {

    private final TradingStatsUseCase tradingStats;

    @Operation(summary = "수익 통계 요약", description = "실현·미실현 손익과 전략 타입별 사이클 성과 집계.")
    @GetMapping("/summary")
    public StatsSummaryResponse getSummary(@AuthenticationPrincipal UUID userId) {
        return StatsSummaryResponse.from(tradingStats.getSummary(userId));
    }

    @Operation(summary = "누적 자산 곡선", description = "일별 전략 운용 자산·원금.")
    @GetMapping("/equity-curve")
    public EquityCurveResponse getEquityCurve(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) StrategyType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return EquityCurveResponse.from(tradingStats.getEquityCurve(userId, type, from, to));
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
                tradingStats.getCyclePerformances(userId, type, cursorInstant, Math.clamp(size, 1, 200)));
    }
}
