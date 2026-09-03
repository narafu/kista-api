package com.kista.strategyconfig.domain.model;

import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// 계좌별 영속 전략 설정 — 여러 StrategyCycle(매매 라운드)을 거느림
public record Strategy(
        UUID id,                    // PK
        UUID accountId,             // FK → accounts.id
        StrategyType type,                  // 매매 전략 종류
        StrategyStatus status,              // 전략 실행 상태
        StrategyTicker ticker,              // 거래 종목 (매매 도메인 메타: 익절률 + 설명)
        StrategyCycleSeedType cycleSeedType // 사이클 종료 후 자동 재등록 정책
) {
    // 상태만 교체 — 나머지 필드 보존
    public Strategy withStatus(StrategyStatus newStatus) {
        return new Strategy(id, accountId, type, newStatus, ticker, cycleSeedType);
    }

    // 연속 정책만 교체
    public Strategy withCycleSeedType(StrategyCycleSeedType newCycleSeedType) {
        return new Strategy(id, accountId, type, status, ticker, newCycleSeedType);
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

    public boolean isActive() {
        return status == StrategyStatus.ACTIVE;
    }

    public boolean isPaused() {
        return status == StrategyStatus.PAUSED;
    }
}
