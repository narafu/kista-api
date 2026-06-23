package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;

// 브로커 어댑터 루트 인터페이스 — identity(어떤 브로커인지)만 정의
// 실제 기능은 Capability 인터페이스를 추가 구현하여 선언
public interface BrokerAdapterPort {
    Account.Broker supports();
}
