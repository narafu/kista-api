package com.kista.adapter.in.web;

import com.kista.domain.model.DailyTransactionResult;
import com.kista.domain.model.Execution;
import com.kista.domain.model.MarginItem;
import com.kista.domain.model.PeriodProfitResult;
import com.kista.domain.model.PresentBalanceResult;
import com.kista.domain.model.ReservationOrder;
import com.kista.domain.port.in.GetAccountStatisticsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts/{accountId}")
@RequiredArgsConstructor
public class StatisticsController {

    private final GetAccountStatisticsUseCase statisticsUseCase;

    // 기간손익 조회 (TTTS3039R)
    @GetMapping("/profit")
    public PeriodProfitResult getPeriodProfit(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
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
    @GetMapping("/trades")
    public List<Execution> getTrades(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
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
    @GetMapping("/portfolio")
    public PresentBalanceResult getPresentBalance(@PathVariable UUID accountId,
                                                  @AuthenticationPrincipal UUID userId) {
        try {
            return statisticsUseCase.getPresentBalance(accountId, userId);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KIS API 호출 실패: " + e.getMessage());
        }
    }

    // 해외증거금 통화별조회 (TTTC2101R) — USD·KRW만 반환
    @GetMapping("/margin")
    public List<MarginItem> getMargin(@PathVariable UUID accountId,
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
    @GetMapping("/daily-trades")
    public DailyTransactionResult getDailyTransactions(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
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
    @GetMapping("/reservation-orders")
    public List<ReservationOrder> getReservationOrders(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
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
}
