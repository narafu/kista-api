package com.kista.admin.domain.model;

import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// trading.domain.model.Strategy의 admin own-type read model — TradingQueryHttpAdapter가
// trading-core 내부 API(/api/internal/trading/accounts/{id}/strategies 등) 응답을 이 타입으로
// 역직렬화한다. isActive()/isPaused()는 AdminQueryService.getAnomalies가 Strategy::isActive/
// isPaused를 그대로 대체하기 위한 헬퍼다.
public record AdminStrategyView(
        UUID id,
        UUID accountId,
        StrategyType type,
        StrategyStatus status,
        StrategyTicker ticker,
        StrategyCycleSeedType cycleSeedType
) {
    public boolean isActive() {
        return status == StrategyStatus.ACTIVE;
    }

    public boolean isPaused() {
        return status == StrategyStatus.PAUSED;
    }
}
