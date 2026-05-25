package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.PortfolioSnapshot;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PortfolioSnapshotResponse(
        @Schema(description = "스냅샷 고유 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID id,
        @Schema(description = "스냅샷 날짜", example = "2025-01-15")
        LocalDate snapshotDate,
        @Schema(description = "거래 종목", example = "SOXL")
        Ticker ticker,
        @Schema(description = "보유 수량", example = "30")
        int holdings,
        @Schema(description = "평균매입단가 (USD)", example = "72.50")
        BigDecimal avgPrice,
        @Schema(description = "평가금액 (USD)", example = "2556.00")
        BigDecimal marketValueUsd,
        @Schema(description = "예수금 (통합주문가능금액, USD)", example = "500.00")
        BigDecimal usdDeposit,
        @Schema(description = "총 자산 (평가금액 + 예수금, USD)", example = "3056.00")
        BigDecimal totalAssetUsd,
        @Schema(description = "스냅샷 생성 일시 (UTC)", example = "2025-01-15T07:00:00Z")
        Instant createdAt
) {
    public static PortfolioSnapshotResponse from(PortfolioSnapshot s) {
        return new PortfolioSnapshotResponse(
                s.id(), s.snapshotDate(), s.ticker(), s.holdings(),
                s.avgPrice(), s.marketValueUsd(),
                s.usdDeposit(), s.totalAssetUsd(), s.createdAt());
    }
}
