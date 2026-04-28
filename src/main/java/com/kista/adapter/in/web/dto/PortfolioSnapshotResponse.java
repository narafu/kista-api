package com.kista.adapter.in.web.dto;

import com.kista.domain.model.PortfolioSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PortfolioSnapshotResponse(
        UUID id, LocalDate snapshotDate, String symbol, int qty,
        BigDecimal avgPrice, BigDecimal currentPrice, BigDecimal marketValueUsd,
        BigDecimal usdDeposit, BigDecimal totalAssetUsd, Instant createdAt
) {
    public static PortfolioSnapshotResponse from(PortfolioSnapshot s) {
        return new PortfolioSnapshotResponse(
                s.id(), s.snapshotDate(), s.symbol(), s.qty(),
                s.avgPrice(), s.currentPrice(), s.marketValueUsd(),
                s.usdDeposit(), s.totalAssetUsd(), s.createdAt());
    }
}
