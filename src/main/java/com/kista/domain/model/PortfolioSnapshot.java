package com.kista.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PortfolioSnapshot(
        UUID id,
        LocalDate snapshotDate,
        String symbol,
        int qty,
        BigDecimal avgPrice,
        BigDecimal currentPrice,
        BigDecimal marketValueUsd,
        BigDecimal usdDeposit,
        BigDecimal totalAssetUsd,
        Instant createdAt
) {}
