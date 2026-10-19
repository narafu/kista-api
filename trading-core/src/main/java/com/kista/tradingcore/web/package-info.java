// trading-core 앱셸 — root com.kista.api.web과 대칭인 프로세스 부팅·전역 web 관심사 모듈:
// 프로세스 전역 예외→HTTP 매핑(TradingExceptionHandler), 프로세스 고유 라우트 정책(TradingSecurityPolicy).
// CLOSED이되 NamedInterface 0개 — 아무 모듈도 tradingcore.web을 참조하지 않는 sink라 모든 trading-core 모듈에 의존해도
// 순환에 참여할 수 없다(root web과 동일 원칙).
// 라우트 정책 — TradingSecurityPolicy가 GET /api/crypto-market-indicators(비인증 대시보드용 공개 시장 지표)만 permitAll로 기여하고,
// 나머지는 platform.security.SecurityConfig 공통 규칙(내부/관리자/anyRequest authenticated)을 따른다.
@org.springframework.modulith.ApplicationModule(
    id = "web",
    type = org.springframework.modulith.ApplicationModule.Type.CLOSED
)
package com.kista.tradingcore.web;
