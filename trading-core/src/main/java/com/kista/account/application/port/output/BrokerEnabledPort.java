package com.kista.account.application.port.output;

import com.kista.sharedkernel.Broker;

// 증권사 신규 계좌 등록·연결 테스트 허용 여부 조회 — AccountService.register()/test()가 소비한다.
// 포트는 필요로 하는 쪽(account)이 정의하고, 정책 데이터를 가진 trading(TradingPolicyService)이 구현한다.
public interface BrokerEnabledPort {
    boolean enabled(Broker broker);
}
