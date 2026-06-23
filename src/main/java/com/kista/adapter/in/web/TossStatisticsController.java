package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.TossStatisticsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "통계 (Toss 전용)", description = "Toss 증권 전용 API 엔드포인트")
@RestController
@RequestMapping("/api/accounts/{accountId}")
@RequiredArgsConstructor
public class TossStatisticsController {

    private final TossStatisticsUseCase tossStatistics;

    @Operation(summary = "캔들차트 조회 (Toss 전용)", description = "Toss API — 지정 종목의 OHLCV 캔들 데이터 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/candles")
    public List<TossCandleResponse> getCandles(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "종목 코드", example = "SOXL") @RequestParam Ticker ticker,
            @Parameter(description = "간격 (1D/1W/1M)", example = "1D") @RequestParam String interval,
            @Parameter(description = "조회 시작일", example = "2025-01-01") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return TossCandleResponse.fromList(tossStatistics.getCandles(accountId, userId, ticker, interval, from, to));
    }

    @Operation(summary = "종목 기본정보 조회 (Toss 전용)", description = "Toss API — 종목명·거래소·통화 등 기본 정보 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/stock-info")
    public TossStockInfoResponse getStockInfo(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "종목 코드", example = "SOXL") @RequestParam Ticker ticker) {
        return TossStockInfoResponse.from(tossStatistics.getStockInfo(accountId, userId, ticker));
    }

    @Operation(summary = "환율 조회 (Toss 전용)", description = "Toss API — USD/KRW 매수 환율 및 매매기준율 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/exchange-rate")
    public TossExchangeRateResponse getExchangeRate(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return TossExchangeRateResponse.from(tossStatistics.getExchangeRate(accountId, userId));
    }

    @Operation(summary = "장 운영 일정 조회 (Toss 전용)", description = "Toss API — 지정 기간 미국 시장 개장 여부 및 프리/정규/애프터마켓 시간 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/market-calendar")
    public List<TossMarketSessionResponse> getMarketCalendar(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일", example = "2025-01-01") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return TossMarketSessionResponse.fromList(tossStatistics.getMarketCalendar(accountId, userId, from, to));
    }

    @Operation(summary = "증권사 계좌 목록 조회 (Toss 전용)", description = "Toss API — 연결된 증권사 계좌 일련번호·계좌번호 목록 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/broker-accounts")
    public List<TossAccountInfoResponse> getBrokerAccounts(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return TossAccountInfoResponse.fromList(tossStatistics.getAccountList(accountId, userId));
    }
}
