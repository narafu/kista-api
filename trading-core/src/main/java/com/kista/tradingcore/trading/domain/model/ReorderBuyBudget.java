package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

// BUY 재주문 예산 — 사이클별 남은 원장 한도(cycles)와 원본 주문별 환급분(sourceRefunds)
// 재주문 가용 = cycles[원본 사이클].remaining + Σ sourceRefunds[재주문할 원본]
public record ReorderBuyBudget(
        Map<UUID, CycleBudget> cycles,        // strategyCycleId별 예산
        Map<UUID, BigDecimal> sourceRefunds   // 원본 주문별 환급분(취소로 풀려나는 BUY 금액)
) {
    // remaining: 최신 원장 − 같은 거래일 저장된 BUY (StrategyLedgerBudget.remaining)
    public record CycleBudget(BigDecimal remaining) {}
}
