package com.kista.domain.model.strategy;

import java.math.BigDecimal;
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
) {}
