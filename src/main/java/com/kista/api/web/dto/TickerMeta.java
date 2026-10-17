package com.kista.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import com.kista.shared.kernel.StrategyTicker;

public record TickerMeta(
        @Schema(description = "enum name() 값", example = "SOXL")
        String code,                   // enum name() 값
        @Schema(description = "종목 설명")
        String description,            // 종목 설명
        @Schema(description = "익절 목표 수익률")
        BigDecimal targetProfitRate,   // 익절 목표 수익률
        @Schema(description = "거래 시장", example = "US_STOCK")
        String market                  // Market name() — 계좌 시장과 일치하는 종목만 선택 가능
) {
    public static TickerMeta from(StrategyTicker t) {
        return new TickerMeta(
                t.name(), t.getDescription(), t.getTargetProfitRate(), t.getMarket().name()
        );
    }
}
