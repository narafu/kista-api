package com.kista.account.application.port.output;

import com.kista.sharedkernel.Broker;

// 증권사 신규 계좌 등록 활성화 여부를 조회하는 포트 — AccountService.register()/test()가
// admin의 RuntimeSettingsPort를 직접 참조하지 않도록, account가 필요한 만큼만 담은 전용 포트를
// 자체 정의하고 admin이 구현한다(user의 ApprovalPolicyPort와 동일한 포트 역전 패턴).
public interface BrokerEnabledPort {
    boolean enabled(Broker broker);
}
