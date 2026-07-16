package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

public record TickerMeta(
        @Schema(description = "enum name() 값", example = "SOXL")
        String code,                   // enum name() 값
        @Schema(description = "종목 설명")
        String description,            // 종목 설명
        @Schema(description = "익절 목표 수익률")
        BigDecimal targetProfitRate    // 익절 목표 수익률
) {
    public static TickerMeta from(Strategy.Ticker t) {
        return new TickerMeta(
                t.name(), t.getDescription(), t.getTargetProfitRate()
        );
    }
}
