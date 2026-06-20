package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.TossAccountInfoResponse;
import com.kista.adapter.in.web.dto.TossCandleResponse;
import com.kista.adapter.in.web.dto.TossExchangeRateResponse;
import com.kista.adapter.in.web.dto.TossMarketSessionResponse;
import com.kista.adapter.in.web.dto.TossSellableQuantityResponse;
import com.kista.adapter.in.web.dto.TossStockInfoResponse;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "토스증권 통계", description = "Toss API 기반 시세·종목·환율·장운영·계좌 조회 (Toss 계좌 전용)")
@RestController
@RequestMapping("/api/accounts/{accountId}/toss")
@RequiredArgsConstructor
public class TossStatisticsController {

    private final AccountStatisticsUseCase accountStatistics;

    // 캔들차트 조회 (GET /api/v1/candles)
    @Operation(
            summary = "캔들차트 조회",
            description = "Toss API GET /api/v1/candles — 지정 종목·기간의 캔들 데이터 반환. Toss 계좌 전용."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/candles")
    public List<TossCandleResponse> getCandles(
            @Parameter(description = "계좌 ID") @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "종목 코드 (예: SOXL)") @RequestParam Ticker ticker,
            @Parameter(description = "봉 단위 (예: 1d, 1w)") @RequestParam(defaultValue = "1d") String interval,
            @Parameter(description = "조회 시작일") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return TossCandleResponse.fromList(
                accountStatistics.getTossCandles(accountId, userId, ticker, interval, from, to));
    }

    // 종목 기본 정보 조회 (GET /api/v1/stocks)
    @Operation(
            summary = "종목 기본 정보 조회",
            description = "Toss API GET /api/v1/stocks — 종목명·거래소·현재가·전일종가 반환. Toss 계좌 전용."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/stocks")
    public TossStockInfoResponse getStockInfo(
            @Parameter(description = "계좌 ID") @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "종목 코드 (예: SOXL)") @RequestParam Ticker ticker) {
        return TossStockInfoResponse.from(
                accountStatistics.getTossStockInfo(accountId, userId, ticker));
    }

    // 환율 조회 (GET /api/v1/exchange-rate)
    @Operation(
            summary = "환율 조회",
            description = "Toss API GET /api/v1/exchange-rate — USD/KRW 매수 환율·매매기준율 반환. Toss 계좌 전용."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/exchange-rate")
    public TossExchangeRateResponse getExchangeRate(
            @Parameter(description = "계좌 ID") @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return TossExchangeRateResponse.from(
                accountStatistics.getTossExchangeRate(accountId, userId));
    }

    // 해외 장 운영 정보 조회 (GET /api/v1/market-calendar/US)
    @Operation(
            summary = "해외 장 운영 정보 조회",
            description = "Toss API GET /api/v1/market-calendar/US — 날짜별 개장·휴장 정보 반환. Toss 계좌 전용."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/market-calendar")
    public List<TossMarketSessionResponse> getMarketCalendar(
            @Parameter(description = "계좌 ID") @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return TossMarketSessionResponse.fromList(
                accountStatistics.getTossMarketCalendar(accountId, userId, from, to));
    }

    // 계좌 목록 조회 (GET /api/v1/accounts)
    @Operation(
            summary = "Toss 계좌 목록 조회",
            description = "Toss API GET /api/v1/accounts — 해당 Client ID에 연결된 계좌 목록 반환. Toss 계좌 전용."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/account-list")
    public List<TossAccountInfoResponse> getAccountList(
            @Parameter(description = "계좌 ID") @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return TossAccountInfoResponse.fromList(
                accountStatistics.getTossAccountList(accountId, userId));
    }

    // 현재가 조회 (GET /api/v1/prices) — StatisticsController.getPrices와 동일 기능, Toss 경로 제공
    @Operation(
            summary = "현재가 조회",
            description = "Toss API GET /api/v1/prices — 종목 현재가 반환. Toss·KIS 공용 (StatisticsController /prices와 동일)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/prices")
    public com.kista.adapter.in.web.dto.MultiPriceResponse getPrices(
            @Parameter(description = "계좌 ID") @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "종목 코드 목록") @RequestParam List<Ticker> tickers) {
        List<Ticker> distinct = tickers.stream().distinct().toList();
        if (distinct.isEmpty() || distinct.size() > 10) {
            throw new IllegalArgumentException("tickers는 1~10개여야 합니다");
        }
        return com.kista.adapter.in.web.dto.MultiPriceResponse.from(
                accountStatistics.getPrices(accountId, userId, distinct));
    }

    // 보유 주식 조회 (GET /api/v1/holdings) — portfolio 엔드포인트의 Toss 전용 alias
    @Operation(
            summary = "보유 주식 조회",
            description = "Toss API GET /api/v1/holdings — 보유 종목·평가손익·예수금 반환. Toss 계좌 전용."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/holdings")
    public com.kista.adapter.in.web.dto.PortfolioSummaryResponse getHoldings(
            @Parameter(description = "계좌 ID") @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return com.kista.adapter.in.web.dto.PortfolioSummaryResponse.from(
                accountStatistics.getPresentBalance(accountId, userId));
    }

    // 매수 가능 금액 조회 (GET /api/v1/buying-power) — margin 엔드포인트의 Toss 전용 alias
    @Operation(
            summary = "매수 가능 금액 조회",
            description = "Toss API GET /api/v1/buying-power — USD·KRW 통화별 매수 가능 금액 반환. Toss 계좌 전용."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/buying-power")
    public List<com.kista.adapter.in.web.dto.MarginResponse> getBuyingPower(
            @Parameter(description = "계좌 ID") @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return accountStatistics.getMargin(accountId, userId)
                .stream().map(com.kista.adapter.in.web.dto.MarginResponse::from).toList();
    }

    // 판매 가능 수량 조회 (GET /api/v1/sellable-quantity)
    @Operation(
            summary = "판매 가능 수량 조회",
            description = "Toss API GET /api/v1/sellable-quantity — 해당 종목의 판매 가능 수량 반환. Toss 계좌 전용."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/sellable-quantity")
    public TossSellableQuantityResponse getSellableQuantity(
            @Parameter(description = "계좌 ID") @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "종목 코드 (예: SOXL)") @RequestParam Ticker ticker) {
        return TossSellableQuantityResponse.from(
                accountStatistics.getTossSellableQuantity(accountId, userId, ticker));
    }
}
