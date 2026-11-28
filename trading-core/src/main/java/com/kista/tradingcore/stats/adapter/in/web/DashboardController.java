package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.tradingcore.stats.adapter.in.web.dto.CycleHistoryResponse;
import com.kista.tradingcore.stats.adapter.in.web.dto.DailyTransactionResponse;
import com.kista.tradingcore.stats.application.service.AccountStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.kista.shared.platform.page.CursorPage;
import com.kista.shared.platform.page.PageQuery;
import com.kista.tradingcore.trading.domain.model.CycleHistoryPage;
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

    private final AccountStatisticsService accountStatistics;

    // 사이클 이력 (DB 기반, 커서 페이지네이션)
    @Operation(summary = "사이클 이력 조회", description = "[페이지형] DB의 strategy_cycle 기반 계좌 거래 이력 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음")
    })
    @GetMapping("/accounts/{accountId}/cycle-history")
    public CursorPage<CycleHistoryResponse> getCycleHistory(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        // size 기본값·상한은 PageQuery가 정한다
        PageQuery query = PageQuery.of(cursor, size);
        Instant cursorInstant = query.cursor() != null ? Instant.parse(query.cursor()) : null;
        CycleHistoryPage page = accountStatistics.getByAccount(accountId, userId, from, to, cursorInstant, query.size());
        return new CursorPage<>(page.items().stream().map(CycleHistoryResponse::from).toList(),
                page.nextCursor() != null ? page.nextCursor().toString() : null, page.hasMore());
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
