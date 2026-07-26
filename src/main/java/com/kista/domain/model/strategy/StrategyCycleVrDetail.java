package com.kista.domain.model.strategy;

import java.math.BigDecimal;
import java.util.UUID;

// VR 사이클 상세 — strategy_cycle_vr 테이블과 매핑 (사이클 시작 시 스냅샷)
public record StrategyCycleVrDetail(
        UUID strategyCycleId,    // FK → strategy_cycle.id
        BigDecimal value,        // 사이클 시작 시 V값 (실력 기준선)
        int gradient,            // 사이클에 고정된 G (StrategyVrDetail.gradientAt() 스냅샷)
        BigDecimal poolLimitRate // 사이클에 고정된 pool 상한 비율(gradientAt/poolLimitRateAt 스냅샷)
) {}
