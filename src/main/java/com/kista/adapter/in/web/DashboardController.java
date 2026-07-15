package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.CycleHistoryPageResponse;
import com.kista.adapter.in.web.dto.DailyTransactionResponse;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "대시보드", description = "사이클 이력 조회")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final AccountStatisticsUseCase accountStatistics;

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

    // 유저 스코프 일별 거래내역 (대시보드 위젯용 — 계좌별 개별 조회 대신 1회 배치 조회)
    @Operation(summary = "유저 스코프 일별 거래내역 조회", description = "지정 기간 동안 요청자가 보유한 전체 계좌의 체결 내역을 계좌 구분 없이 합쳐 반환.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/daily-trades")
    public DailyTransactionResponse getDailyTransactions(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일", example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return DailyTransactionResponse.from(accountStatistics.getDailyTransactionsForUser(userId, from, to));
    }
}
