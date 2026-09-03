package com.kista.user.application.port.output;

import java.util.UUID;

// 사용자의 전 계좌 ACTIVE 전략 총 개수 — 잔고검증 OFF→ON/ON→OFF 전환 경고 로그용.
// user가 strategy-config의 StrategyPort를 직접 참조하던 것을 own-type 포트 역전으로 해소
// (account BrokerEnabledPort/admin ApprovalPolicyPort와 동일 패턴). strategy-config가 구현한다.
public interface ActiveStrategyCountPort {
    long countActiveByUserId(UUID userId);
}
