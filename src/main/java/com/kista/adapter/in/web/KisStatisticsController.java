package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.CycleHistoryPageResponse;
import com.kista.adapter.in.web.dto.DailyTransactionResponse;
import com.kista.adapter.in.web.dto.ExecutionResponse;
import com.kista.adapter.in.web.dto.MarginResponse;
import com.kista.adapter.in.web.dto.MultiPriceResponse;
import com.kista.adapter.in.web.dto.PeriodProfitResponse;
import com.kista.adapter.in.web.dto.PortfolioSnapshotResponse;
import com.kista.adapter.in.web.dto.PortfolioSummaryResponse;
import com.kista.domain.model.strategy.Strategy.Ticker;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "통계", description = "KIS API 기반 계좌별 손익·체결·잔고·증거금 조회")
@RestController
@RequestMapping("/api/accounts/{accountId}")
@RequiredArgsConstructor
public class KisStatisticsController {

    private final AccountStatisticsUseCase accountStatistics;

    // 기간손익 조회 (TTTS3039R)
    @Operation(summary = "기간손익 조회", description = "KIS API TTTS3039R — 지정 기간 동안의 종목별 실현손익 및 수익률 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "KIS API 호출 실패")
    })
    @GetMapping("/profit")
    public PeriodProfitResponse getPeriodProfit(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일", example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return PeriodProfitResponse.from(accountStatistics.getPeriodProfit(accountId, userId, from, to));
    }

    // 체결 내역 조회 (TTTS3035R)
    @Operation(summary = "체결 내역 조회", description = "KIS API TTTS3035R — 지정 기간 동안의 체결된 주문 목록 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "KIS API 호출 실패")
    })
    @GetMapping("/trades")
    public List<ExecutionResponse> getTrades(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일", example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return accountStatistics.getExecutions(accountId, userId, from, to)
                .stream().map(ExecutionResponse::from).toList();
    }

    // 체결기준현재잔고 조회 (CTRP6504R)
    @Operation(summary = "현재 잔고 조회", description = "KIS API CTRP6504R — 체결 기준 현재 보유 종목별 잔고 및 평가손익 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "KIS API 호출 실패")
    })
    @GetMapping("/portfolio")
    public PortfolioSummaryResponse getPresentBalance(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return PortfolioSummaryResponse.from(accountStatistics.getPresentBalance(accountId, userId));
    }

    // 해외증거금 통화별조회 (TTTC2101R) — USD·KRW만 반환
    @Operation(summary = "해외증거금 조회", description = "KIS API TTTC2101R — USD·KRW 통화별 통합주문가능금액(예수금) 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "KIS API 호출 실패")
    })
    @GetMapping("/margin")
    public List<MarginResponse> getMargin(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return accountStatistics.getMargin(accountId, userId)
                .stream().map(MarginResponse::from).toList();
    }

    // 일별거래내역 조회 (CTOS4001R)
    @Operation(summary = "일별 거래내역 조회", description = "KIS API CTOS4001R — 지정 기간 동안의 일별 거래내역 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "KIS API 호출 실패")
    })
    @GetMapping("/daily-trades")
    public DailyTransactionResponse getDailyTransactions(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일", example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return DailyTransactionResponse.from(accountStatistics.getDailyTransactions(accountId, userId, from, to));
    }

    // 계좌 기준 포지션 스냅샷 목록 (DB 기반, KIS API 미사용 — 차트용 시계열)
    @Operation(summary = "계좌 스냅샷 목록", description = "cycle_position DB 기반 계좌별 포지션 이력 반환. KIS API 미호출.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음")
    })
    @GetMapping("/snapshots")
    public List<PortfolioSnapshotResponse> getSnapshots(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일 (생략 시 전체)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (생략 시 오늘)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return accountStatistics.getSnapshotsByAccount(accountId, userId, from, to)
                .stream().map(PortfolioSnapshotResponse::from).toList();
    }

    // trading_cycle_history DB 조회 (KIS API 미사용) — 커서 기반 페이지네이션
    @Operation(summary = "사이클 이력 조회", description = "DB의 trading_cycle_history 테이블 기반 계좌 거래 이력 조회. KIS API 미호출.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음")
    })
    @GetMapping("/cycle-history")
    public CycleHistoryPageResponse getCycleHistory(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일 (생략 시 전체)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (생략 시 전체)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "커서 (이전 응답의 nextCursor)")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기 (기본 50, 최대 200)")
            @RequestParam(defaultValue = "50") int size) {
        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        return CycleHistoryPageResponse.from(
                accountStatistics.getByAccount(accountId, userId, from, to, cursorInstant, Math.min(size, 200)));
    }

    // 복수 종목 현재가 조회 (HHDFS76410000)
    @Operation(
            summary = "복수 종목 현재가 조회",
            description = "KIS API HHDFS76410000 — 최대 10개 종목의 현재가를 단일 호출로 조회. tickers는 TQQQ/SOXL/USD 중 1~10개."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "tickers 1~10개 필요 또는 유효하지 않은 종목"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "KIS API 호출 실패")
    })
    @GetMapping("/prices")
    public MultiPriceResponse getPrices(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회할 종목 코드 목록 (TQQQ/SOXL/USD)", example = "TQQQ,SOXL,USD")
            @RequestParam List<Ticker> tickers) {
        List<Ticker> distinct = tickers.stream().distinct().toList();
        if (distinct.isEmpty() || distinct.size() > 10) {
            throw new IllegalArgumentException("tickers는 1~10개여야 합니다"); // GlobalExceptionHandler → 400
        }
        Map<Ticker, BigDecimal> result = accountStatistics.getPrices(accountId, userId, distinct);
        return MultiPriceResponse.from(result);
    }
}
