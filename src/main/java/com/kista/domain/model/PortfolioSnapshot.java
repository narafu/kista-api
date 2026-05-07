package com.kista.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PortfolioSnapshot(
        UUID id,                   // 스냅샷 고유 ID (DB 저장 전 null)
        LocalDate snapshotDate,    // 스냅샷 기준일
        String symbol,             // 종목 코드
        int qty,                   // 보유 수량
        BigDecimal avgPrice,       // 평균 매입가 (USD)
        BigDecimal currentPrice,   // 현재 시장가 (USD)
        BigDecimal marketValueUsd, // 평가액 (USD) = currentPrice × qty
        BigDecimal usdDeposit,     // USD 예수금
        BigDecimal totalAssetUsd,  // 총 자산 (USD) = marketValueUsd + usdDeposit
        UUID accountId,            // FK → accounts(id), V8에서 추가 (nullable)
        Instant createdAt          // DB 저장 시각 (DB DEFAULT, 저장 전 null)
) {}
