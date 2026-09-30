package com.kista.trading.application.port.output;

import com.kista.sharedkernel.StrategyCreationSettings;
import com.kista.sharedkernel.StrategyType;

import java.util.Optional;

// 전략 등록 시 타입별 생성 정책(활성화 여부·필드 허용값) 조회 — StrategyCreationService가 소비한다.
// 정책은 trading-core가 소유(TradingPolicySettingsPort 저장소)하며 TradingPolicyService가 이 포트를 구현한다.
public interface StrategyCreationPolicyPort {
    Optional<StrategyCreationSettings> find(StrategyType type);
}
