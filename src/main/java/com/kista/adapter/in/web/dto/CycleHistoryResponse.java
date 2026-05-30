package com.kista.adapter.in.web.dto;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;

import java.math.BigDecimal;

public record CycleHistoryResponse(
        String createdAt,       // ISO-8601 문자열
        String ticker,          // 종목 코드 (TQQQ, SOXL 등)
        int holdings,           // 보유 수량
        BigDecimal avgPrice,    // 평균 매입 단가 (보유수량 0이면 null)
        BigDecimal usdDeposit   // 통합주문가능금액
) {
    public static CycleHistoryResponse from(AccountCycleHistoryEntry e) {
        return new CycleHistoryResponse(
                e.createdAt().toString(),
                e.ticker() != null ? e.ticker().name() : null,
                e.holdings(),
                e.avgPrice(),
                e.usdDeposit()
        );
    }
}
