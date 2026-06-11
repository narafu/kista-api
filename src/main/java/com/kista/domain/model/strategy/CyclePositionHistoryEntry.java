package com.kista.domain.model.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

// cycle_position + strategy_cycle + strategy join 조회 결과 (계좌·사이클 기준 이력 조회 전용)
public record CyclePositionHistoryEntry(
        UUID id,
        Strategy.Ticker ticker,    // strategy.ticker
        BigDecimal usdDeposit,     // 통합주문가능금액
        BigDecimal closingPrice,   // 종가 (null 가능)
        BigDecimal avgPrice,       // 평균 매입 단가 (holdings=0이면 null)
        int holdings,              // 보유 수량
        Instant createdAt          // 기록 시각
) {
    // 평가금액 = closingPrice × holdings (closingPrice 없으면 0)
    public BigDecimal marketValueUsd() {
        return closingPrice != null
                ? closingPrice.multiply(BigDecimal.valueOf(holdings)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    // 총 자산 = 평가금액 + 예수금
    public BigDecimal totalAssetUsd() {
        return marketValueUsd().add(usdDeposit).setScale(2, RoundingMode.HALF_UP);
    }
}
