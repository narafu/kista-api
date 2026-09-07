package com.kista.trading.application.port.output;

import com.kista.sharedkernel.StrategyCreationSettings;
import com.kista.sharedkernel.StrategyType;

import java.util.Optional;

// 전략 등록 시 타입별 생성 정책(활성화 여부·필드 허용값) 조회 — strategy-config가 admin의 RuntimeSettingsPort를
// 직접 참조하지 않도록 포트 역전(user ApprovalPolicyPort/account BrokerEnabledPort와 동일 패턴)으로 소비한다.
// admin의 RuntimeSettingsService가 구현. StrategyCreationSettings는 sharedkernel 공용 타입이라 매핑 없이 전달.
public interface StrategyCreationPolicyPort {
    Optional<StrategyCreationSettings> find(StrategyType type);
}
