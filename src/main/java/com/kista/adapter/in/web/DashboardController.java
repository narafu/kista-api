package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.CycleHistoryPageResponse;
import com.kista.adapter.in.web.dto.PortfolioSnapshotResponse;
import com.kista.adapter.in.web.dto.TradeHistoryResponse;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import com.kista.domain.port.in.PortfolioUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.kista.common.TimeZones;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "대시보드", description = "거래 내역, 포트폴리오 스냅샷 조회")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final PortfolioUseCase portfolioUseCase;
    private final AccountStatisticsUseCase accountStatistics;

    @Operation(summary = "거래 내역 조회", description = "날짜 범위와 종목으로 필터링. 기본: 최근 30일, 종목 SOXL.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/trades")
    public List<TradeHistoryResponse> getTrades(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일 (기본: 오늘 - 30일)", example = "2025-01-01")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (기본: 오늘)", example = "2025-01-31")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "거래 종목", example = "SOXL")
            @RequestParam(defaultValue = "SOXL") Ticker ticker) {
        return portfolioUseCase.getHistory(userId, resolveFrom(from), resolveTo(to), ticker)
                .stream().map(TradeHistoryResponse::from).toList();
    }

    @Operation(summary = "현재 포트폴리오 조회", description = "가장 최근 포트폴리오 스냅샷 1건 반환.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/portfolio/current")
    public PortfolioSnapshotResponse getPortfolioCurrent(@AuthenticationPrincipal UUID userId) {
        return PortfolioSnapshotResponse.from(portfolioUseCase.getCurrent(userId));
    }

    @Operation(summary = "포트폴리오 스냅샷 목록", description = "지정 기간의 포트폴리오 스냅샷 목록 반환. 기본: 최근 30일.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/portfolio/snapshots")
    public List<PortfolioSnapshotResponse> getPortfolioSnapshots(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일 (기본: 오늘 - 30일)", example = "2025-01-01")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (기본: 오늘)", example = "2025-01-31")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return portfolioUseCase.getSnapshots(userId, resolveFrom(from), resolveTo(to))
                .stream().map(PortfolioSnapshotResponse::from).toList();
    }

    // 계좌별 포지션 스냅샷 목록 (DB 기반, 차트용 시계열)
    @Operation(summary = "계좌 스냅샷 목록", description = "cycle_position DB 기반 계좌별 포지션 이력 반환.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음")
    })
    @GetMapping("/accounts/{accountId}/snapshots")
    public List<PortfolioSnapshotResponse> getAccountSnapshots(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return accountStatistics.getSnapshotsByAccount(accountId, userId, from, to)
                .stream().map(PortfolioSnapshotResponse::from).toList();
    }

    // 사이클 이력 (DB 기반, 커서 페이지네이션)
    @Operation(summary = "사이클 이력 조회", description = "DB의 strategy_cycle 기반 계좌 거래 이력 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음")
    })
    @GetMapping("/accounts/{accountId}/cycle-history")
    public CycleHistoryPageResponse getCycleHistory(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        return CycleHistoryPageResponse.from(
                accountStatistics.getByAccount(accountId, userId, from, to, cursorInstant, Math.min(size, 200)));
    }

    // 조회 시작일 기본값: 오늘 - 30일
    private static LocalDate resolveFrom(LocalDate from) {
        return from != null ? from : LocalDate.now(TimeZones.KST).minusDays(30);
    }

    // 조회 종료일 기본값: 오늘
    private static LocalDate resolveTo(LocalDate to) {
        return to != null ? to : LocalDate.now(TimeZones.KST);
    }
}
