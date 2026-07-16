package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

public record CycleHistoryResponse(
        @Schema(description = "기록 시각 (ISO-8601)")
        String createdAt,        // ISO-8601 문자열
        @Schema(description = "종목 코드", example = "TQQQ")
        String ticker,           // 종목 코드 (TQQQ, SOXL 등)
        @Schema(description = "보유 수량")
        int holdings,            // 보유 수량
        @Schema(description = "종가 (null 가능)")
        BigDecimal closingPrice, // 종가 (null 가능)
        @Schema(description = "평균 매입 단가 (보유수량 0이면 null)")
        BigDecimal avgPrice,     // 평균 매입 단가 (보유수량 0이면 null)
        @Schema(description = "통합주문가능금액")
        BigDecimal usdDeposit    // 통합주문가능금액
) {
    public static CycleHistoryResponse from(CyclePositionHistoryEntry e) {
        return new CycleHistoryResponse(
                e.createdAt().toString(),
                e.ticker() != null ? e.ticker().name() : null,
                e.holdings(),
                e.closingPrice(),
                e.avgPrice(),
                e.usdDeposit()
        );
    }
}
