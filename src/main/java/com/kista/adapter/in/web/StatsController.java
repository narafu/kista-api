package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.CyclePerformancePageResponse;
import com.kista.adapter.in.web.dto.EquityCurveResponse;
import com.kista.adapter.in.web.dto.StatsSummaryResponse;
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

    @Operation(summary = "누적 자산 곡선", description = "일별 전략 운용 자산·원금 + 벤치마크 지수 종가 (KST 거래일).")
    @GetMapping("/equity-curve")
    public EquityCurveResponse getEquityCurve(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "SPY") String benchmark) {
        return EquityCurveResponse.from(userStats.getEquityCurve(userId, from, to, benchmark));
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
                userStats.getCyclePerformances(userId, type, cursorInstant, Math.min(size, 200)));
    }
}
