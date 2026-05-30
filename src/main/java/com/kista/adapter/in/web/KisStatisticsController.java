package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.CycleHistoryResponse;
import com.kista.adapter.in.web.dto.MultiPriceResponse;
import com.kista.adapter.in.web.dto.PortfolioSummaryResponse;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PeriodProfitResult;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.kis.ReservationOrder;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.in.GetAccountStatisticsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Tag(name = "통계", description = "KIS API 기반 계좌별 손익·체결·잔고·증거금·예약주문 조회")
@RestController
@RequestMapping("/api/accounts/{accountId}")
@RequiredArgsConstructor
public class KisStatisticsController {

    private final GetAccountStatisticsUseCase statisticsUseCase;

    // 기간손익 조회 (TTTS3039R)
    @Operation(summary = "기간손익 조회", description = "KIS API TTTS3039R — 지정 기간 동안의 종목별 실현손익 및 수익률 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "KIS API 호출 실패")
    })
    @GetMapping("/profit")
    public PeriodProfitResult getPeriodProfit(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일", example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        try {
            return statisticsUseCase.getPeriodProfit(accountId, userId, from, to);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KIS API 호출 실패: " + e.getMessage());
        }
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
    public List<Execution> getTrades(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일", example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        try {
            return statisticsUseCase.getTrades(accountId, userId, from, to);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KIS API 호출 실패: " + e.getMessage());
        }
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
        try {
            PresentBalanceResult result = statisticsUseCase.getPresentBalance(accountId, userId);
            return normalizePortfolio(result);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KIS API 호출 실패: " + e.getMessage());
        }
    }

    // PresentBalanceResult(KIS raw) → PortfolioSummaryResponse(kista-ui 호환 포맷) 변환
    private PortfolioSummaryResponse normalizePortfolio(PresentBalanceResult result) {
        // output1: 종목별 잔고 → PositionDto 목록으로 변환
        List<PortfolioSummaryResponse.PositionDto> positions = result.items().stream()
                .map(item -> new PortfolioSummaryResponse.PositionDto(
                        item.ticker(),
                        item.holdings(),
                        item.avgPrice(),
                        item.currentPrice(),
                        item.evalAmountUsd(),
                        item.profitLossUsd(),
                        item.profitRate(),
                        item.exchangeCode()
                ))
                .toList();

        // output3: 계좌 전체 요약 → SummaryDto 변환
        PortfolioSummaryResponse.SummaryDto summary = new PortfolioSummaryResponse.SummaryDto(
                result.totalAssetUsd(),
                result.totalEvalProfit(),
                result.totalReturnRate()
        );

        return new PortfolioSummaryResponse(positions, summary);
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
    public List<MarginItem> getMargin(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        try {
            return statisticsUseCase.getMargin(accountId, userId);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KIS API 호출 실패: " + e.getMessage());
        }
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
    public DailyTransactionResult getDailyTransactions(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일", example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        try {
            return statisticsUseCase.getDailyTransactions(accountId, userId, from, to);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KIS API 호출 실패: " + e.getMessage());
        }
    }

    // 예약주문 목록 조회 (TTTT3039R)
    @Operation(summary = "예약주문 목록 조회", description = "KIS API TTTT3039R — 지정 기간 동안의 예약주문 목록 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "KIS API 호출 실패")
    })
    @GetMapping("/reservation-orders")
    public List<ReservationOrder> getReservationOrders(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일", example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        try {
            return statisticsUseCase.getReservationOrders(accountId, userId, from, to);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KIS API 호출 실패: " + e.getMessage());
        }
    }

    // trading_cycle_history DB 조회 (KIS API 미사용)
    @Operation(summary = "사이클 이력 조회", description = "DB의 trading_cycle_history 테이블 기반 계좌 거래 이력 조회. KIS API 미호출.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음")
    })
    @GetMapping("/cycle-history")
    public List<CycleHistoryResponse> getCycleHistory(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일", example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        try {
            List<AccountCycleHistoryEntry> history = statisticsUseCase.getCycleHistory(accountId, userId, from, to);
            return history.stream().map(CycleHistoryResponse::from).toList();
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tickers는 1~10개여야 합니다");
        }
        try {
            Map<Ticker, BigDecimal> result = statisticsUseCase.getPrices(accountId, userId, distinct);
            return MultiPriceResponse.from(result);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KIS API 호출 실패: " + e.getMessage());
        }
    }
}
