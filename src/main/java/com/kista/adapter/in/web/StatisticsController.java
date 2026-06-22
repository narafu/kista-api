package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.DailyTransactionResponse;
import com.kista.adapter.in.web.dto.ExecutionResponse;
import com.kista.adapter.in.web.dto.MarginResponse;
import com.kista.adapter.in.web.dto.MultiPriceResponse;
import com.kista.adapter.in.web.dto.PortfolioSummaryResponse;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "통계", description = "계좌별 손익·체결·잔고·증거금·현재가 조회 (KIS/Toss 브로커 자동 분기)")
@RestController
@RequestMapping("/api/accounts/{accountId}")
@RequiredArgsConstructor
public class StatisticsController {

    private final AccountStatisticsUseCase accountStatistics;

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

    // ── Toss 전용 엔드포인트 ───────────────────────────────────────────────────

    // 캔들차트 조회 (Toss /api/v1/candles)
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
        return TossCandleResponse.fromList(accountStatistics.getTossCandles(accountId, userId, ticker, interval, from, to));
    }

    // 종목 기본정보 조회 (Toss /api/v1/stocks)
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
        return TossStockInfoResponse.from(accountStatistics.getTossStockInfo(accountId, userId, ticker));
    }

    // 환율 조회 (Toss /api/v1/exchange-rate)
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
        return TossExchangeRateResponse.from(accountStatistics.getTossExchangeRate(accountId, userId));
    }

    // 장 운영 일정 조회 (Toss /api/v1/market-calendar/US)
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
        return TossMarketSessionResponse.fromList(accountStatistics.getTossMarketCalendar(accountId, userId, from, to));
    }

    // 증권사 계좌 목록 조회 (Toss /api/v1/accounts)
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
        return TossAccountInfoResponse.fromList(accountStatistics.getTossAccountList(accountId, userId));
    }

    // 판매 가능 수량 조회 (KIS: CTRP6504R 잔고수량 / Toss: /api/v1/sellable-quantity)
    @Operation(summary = "판매 가능 수량 조회", description = "KIS: CTRP6504R 체결기준현재잔고 잔고수량 / Toss: /api/v1/sellable-quantity — 지정 종목의 현재 판매 가능 수량 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "브로커 API 호출 실패")
    })
    @GetMapping("/sellable-quantity")
    public TossSellableQuantityResponse getSellableQuantity(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "종목 코드", example = "SOXL") @RequestParam Ticker ticker) {
        return TossSellableQuantityResponse.from(accountStatistics.getSellableQuantity(accountId, userId, ticker));
    }
}
