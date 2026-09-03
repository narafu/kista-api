package com.kista.strategyconfig.application.port.output;

import com.kista.sharedkernel.StrategyType;
import com.kista.trading.domain.strategy.StrategyCreationSettings;

import java.util.Optional;

// 전략 등록 시 타입별 생성 정책(활성화 여부·필드 허용값) 조회 — strategy-config가 admin의 RuntimeSettingsPort를
// 직접 참조하던 것을 own-type 포트 역전으로 해소(user ApprovalPolicyPort/account BrokerEnabledPort와 동일 패턴,
// 8번째 인스턴스). admin의 RuntimeSettingsService가 구현하며, admin 내부 타입을 이미 trading 소유
// StrategyCreationSettings로 매핑해 반환한다 — strategy-config는 admin을 전혀 참조하지 않는다.
public interface StrategyCreationPolicyPort {
    Optional<StrategyCreationSettings> find(StrategyType type);
}
