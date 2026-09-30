// trading-core 앱셸 — root com.kista.web과 대칭인 프로세스 부팅·전역 web 관심사 모듈:
// 부팅 진입점(TradingApplication)·JPA 리포지토리 배선(JpaRepositoryConfig)·프로세스 전역 예외→HTTP 매핑(TradingExceptionHandler).
// CLOSED이되 NamedInterface 0개 — 아무 모듈도 tradingweb을 참조하지 않는 sink라 모든 trading-core 모듈에 의존해도
// 순환에 참여할 수 없다(root web과 동일 원칙).
// 라우트 정책 기여 없음 — 이 프로세스의 모든 라우트가 인증 필수 또는 /api/internal/** 이라 platform.security.SecurityConfig의
// 공통 규칙(내부/관리자/anyRequest authenticated)만으로 충분하다(SecurityRoutePolicy 빈이 없어도 무해).
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.CLOSED
)
package com.kista.tradingweb;
