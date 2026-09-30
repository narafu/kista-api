// account 모듈의 공개 계약 일부 — *Port 접미사 출력 포트. "port" 이름으로 공개된다.
// AccountPort/BrokerEnabledPort(BrokerEnabledPort는 account가 정의하고 정책 소유자인 trading의 TradingPolicyService가 구현하는 포트 역전).
@org.springframework.modulith.NamedInterface("port")
package com.kista.account.application.port.output;
