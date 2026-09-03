package com.kista.adapter.in.web.dto;

import com.kista.sharedkernel.StrategyTicker;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Schema(description = "복수 종목 현재가 조회 응답")
public record MultiPriceResponse(
        @Schema(description = "종목별 현재가 목록")
        List<TickerPrice> prices
) {
    public record TickerPrice(
            @Schema(description = "종목 코드", example = "TQQQ")
            StrategyTicker ticker,
            @Schema(description = "현재가 (USD)", example = "120.50")
            BigDecimal price
    ) {}

    public static MultiPriceResponse from(Map<StrategyTicker, BigDecimal> priceMap) {
        List<TickerPrice> list = priceMap.entrySet().stream()
                .map(e -> new TickerPrice(e.getKey(), e.getValue()))
                .toList();
        return new MultiPriceResponse(list);
    }
}
