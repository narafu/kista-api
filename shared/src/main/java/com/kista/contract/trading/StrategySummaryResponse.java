package com.kista.contract.trading;

import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// 사이클 ID 기준 전략 요약 — strategy_cycle.id → strategyId + strategy.type 배치 조회 결과
public record StrategySummaryResponse(
        UUID strategyId,            // 전략 ID
        StrategyType strategyType   // 전략 종류
) {}
