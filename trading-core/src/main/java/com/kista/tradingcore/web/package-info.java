// trading-core 전역 web 관심사 모듈 — 오류 보고는 한 애플리케이션의 api.web ApiExceptionReporter 하나가 맡는다(현재 클래스 없음).
// ID는 api.web과 겹치지 않게 trading-web(한 애플리케이션 모델).
// CLOSED이되 NamedInterface 0개 — 아무 모듈도 tradingcore.web을 참조하지 않는 sink라 모든 trading-core 모듈에 의존해도
// 순환에 참여할 수 없다(root web과 동일 원칙).
// 라우트 정책은 따로 기여하지 않고 platform.security.SecurityConfig 공통 규칙(내부/관리자/anyRequest authenticated)을 따른다.
@org.springframework.modulith.ApplicationModule(
    id = "trading-web",
    type = org.springframework.modulith.ApplicationModule.Type.CLOSED
)
package com.kista.tradingcore.web;
