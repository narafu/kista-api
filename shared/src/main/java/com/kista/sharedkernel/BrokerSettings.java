package com.kista.sharedkernel;

// 증권사별 신규 계좌 등록·연결 테스트 허용 정책 — TradingPolicySettings.brokers() 값 타입.
// 소유자는 trading-core(정책이 지키는 불변식이 AccountService.register()에 있음), admin은 편집만 한다.
public record BrokerSettings(boolean enabled) { // 신규 등록 허용 여부
}
