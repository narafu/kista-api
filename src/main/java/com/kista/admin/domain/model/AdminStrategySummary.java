package com.kista.admin.domain.model;

import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// trading.domain.model.StrategySummary own-type — TradingQueryPort 시그니처가 root 소유 타입만
// 쓰도록 :api -> :trading-core 컴파일 의존을 없애기 위한 복제(값 shape 동일, JSON 필드명 일치)
public record AdminStrategySummary(
        UUID strategyId,
        StrategyType strategyType
) {
}
