package com.kista.domain.model.tradingcycle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// trading_cycle_history + trading_cycle join 조회 결과 (계좌 기준 이력 조회 전용)
public record AccountCycleHistoryEntry(
        UUID id,
        TradingCycle.Ticker ticker,    // trading_cycle.ticker
        BigDecimal usdDeposit,         // 통합주문가능금액
        BigDecimal avgPrice,           // 평균 매입 단가 (보유수량 0이면 null)
        int holdings,                  // 보유 수량
        Instant createdAt              // 기록 시각
) {}
