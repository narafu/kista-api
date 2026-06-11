package com.kista.common;

import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.StrategyCyclePort;

import java.util.UUID;

// 활성 StrategyCycle 조회 공통 헬퍼 — 여러 매매 서비스에서 반복되는 orElseThrow 통합
public final class CycleLookups {

    // 전략의 현재 활성 사이클 조회, 없으면 IllegalStateException(400, GlobalExceptionHandler)
    public static StrategyCycle requireLatestCycle(StrategyCyclePort strategyCyclePort, UUID strategyId) {
        return strategyCyclePort.findLatestByStrategyId(strategyId)
                .orElseThrow(() -> new IllegalStateException("활성 사이클 없음: strategyId=" + strategyId));
    }

    private CycleLookups() {}
}
