package com.kista.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import com.kista.shared.kernel.StrategyTicker;

public record TickerMeta(
        @Schema(description = "enum name() 값", example = "SOXL")
        String code,                   // enum name() 값
        @Schema(description = "종목 설명")
        String description,            // 종목 설명
        @Schema(description = "익절 목표 수익률")
        BigDecimal targetProfitRate,   // 익절 목표 수익률
        @Schema(description = "거래 가능 시장 목록 (Market name())")
        List<String> markets           // 계좌 시장이 여기에 포함된 종목만 선택 가능
) {
    public static TickerMeta from(StrategyTicker t) {
        return new TickerMeta(
                t.name(), t.getDescription(), t.getTargetProfitRate(),
                t.getMarkets().stream().map(Enum::name).toList()
        );
    }
}
