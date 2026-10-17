package com.kista.shared.kernel;

// 증권사별 신규 계좌 등록·연결 테스트 허용 정책 — TradingPolicySettings.brokers() 값 타입.
// 소유자는 trading-core(정책이 지키는 불변식이 AccountService.register()에 있음), admin은 편집만 한다.
public record BrokerSettings(
        boolean enabled,  // 신규 등록 허용 여부
        String allowedIp  // 거래소 API 키에 등록할 서버 공인 IP 안내 (업비트만 값, 그 외 null) — 관리자 PUT으로는 바꾸지 않는다
) {
}
