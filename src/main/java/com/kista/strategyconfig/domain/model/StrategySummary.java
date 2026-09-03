package com.kista.strategyconfig.domain.model;

import java.util.UUID;
import com.kista.sharedkernel.StrategyType;

// strategy_cycle.id → strategyId + strategy.type 배치 조회 결과 — strategyconfig 소유 own-type
// admin.domain.model.AdminCycleStrategySummary와 필드 구성 동일(2필드), admin 의존 제거 목적으로 복제
public record StrategySummary(
        UUID strategyId,
        StrategyType strategyType
) {
}
