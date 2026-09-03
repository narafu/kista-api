package com.kista.trading.domain.model;

import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// strategy-config 소유 Strategy의 trading own-type 복제(broker BrokerAccountRef와 동일 패턴) — trading의
// 스케쥴러·실행 코어가 상시 참조하는 6필드만 읽기 전용으로 노출한다. 쓰기(일시정지)는 StrategyPausePort가 담당하므로
// isPaused()/withStatus() 등 trading에서 쓰이지 않는 메서드는 포함하지 않는다(YAGNI).
public record StrategyRef(UUID id, UUID accountId, StrategyType type, StrategyStatus status,
                           StrategyTicker ticker, StrategyCycleSeedType cycleSeedType) {

    public boolean isActive() {
        return status == StrategyStatus.ACTIVE;
    }

    public boolean isInfinite() {
        return type == StrategyType.INFINITE;
    }

    public boolean isPrivacy() {
        return type == StrategyType.PRIVACY;
    }

    public boolean isVr() {
        return type == StrategyType.VR;
    }
}
