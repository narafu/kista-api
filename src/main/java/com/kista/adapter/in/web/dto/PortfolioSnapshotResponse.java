package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PortfolioSnapshotResponse(
        @Schema(description = "이력 고유 ID")
        UUID id,
        @Schema(description = "거래 종목", example = "SOXL")
        Ticker ticker,
        @Schema(description = "보유 수량", example = "30")
        int holdings,
        @Schema(description = "종가 (USD, null이면 PRIVACY 또는 초기 등록)", example = "26.00")
        BigDecimal closingPrice,
        @Schema(description = "평균매입단가 (USD, 보유수량 0이면 null)", example = "25.00")
        BigDecimal avgPrice,
        @Schema(description = "평가금액 (USD) = closingPrice × holdings (closingPrice null이면 0)", example = "780.00")
        BigDecimal marketValueUsd,
        @Schema(description = "예수금 (통합주문가능금액, USD)", example = "500.00")
        BigDecimal usdDeposit,
        @Schema(description = "총 자산 (평가금액 + 예수금, USD)", example = "1280.00")
        BigDecimal totalAssetUsd,
        @Schema(description = "기록 일시 (UTC)", example = "2025-01-15T07:00:00Z")
        Instant createdAt
) {
    public static PortfolioSnapshotResponse from(CyclePositionHistoryEntry e) {
        return new PortfolioSnapshotResponse(
                e.id(), e.ticker(), e.holdings(),
                e.closingPrice(), e.avgPrice(),
                e.marketValueUsd(), e.usdDeposit(), e.totalAssetUsd(),
                e.createdAt());
    }
}
