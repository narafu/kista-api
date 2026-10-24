package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;

// 미리보기 시 전략 원장 대비 BUY 판정 — 배치와 같은 StrategyLedgerBudget 산출
public record LedgerCheckPreview(
        BigDecimal remainingLedger,  // 원장 잔여 (같은 날 저장된 BUY 차감 후)
        BigDecimal requiredBuyUsd,   // 신규 BUY 원본 합계
        boolean sufficient,          // false면 BUY 전체 거절
        boolean trimmed              // true면 원장에 맞춰 BUY 축소
) {}
