package com.kista.domain.model.stats;

import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;

// 전략 타입별 사이클 성과 집계 — 비율 필드는 종료 사이클이 없으면 null
public record StrategyTypeStats(
        Strategy.Type type,
        int closedCycleCount,
        int activeCycleCount,
        BigDecimal winRate,         // 수익 사이클 비율 0~1 (scale 4)
        BigDecimal avgReturnRate,   // 평균 수익률 (scale 4)
        BigDecimal avgDurationDays, // 평균 소요일 (scale 1)
        BigDecimal realizedPnl,     // 누적 실현손익 USD
        BigDecimal unrealizedPnl    // 진행 중 미실현 평가손익 USD
) {}
