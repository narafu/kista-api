package com.kista.tradingcore.stats.adapter.in.web.dto;

import com.kista.shared.kernel.StrategyTicker;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// 코인 현재가 응답 — 주식 /prices(MultiPriceResponse, USD·주식 티커 enum)와 스키마를 분리해 통화·종목 집합을 openapi에 정확히 싣는다
public record CryptoPricesResponse(
        @Schema(description = "종목별 현재가 — 시세가 없는 종목은 빠진다") List<CryptoPrice> prices
) {
    public static CryptoPricesResponse from(Map<StrategyTicker, BigDecimal> prices) {
        return new CryptoPricesResponse(prices.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new CryptoPrice(e.getKey().name(), e.getValue()))
                .toList());
    }

    public record CryptoPrice(
            @Schema(description = "종목", example = "BTC") String ticker,
            @Schema(description = "현재가 (KRW)", example = "150000000") @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal price
    ) {}
}
