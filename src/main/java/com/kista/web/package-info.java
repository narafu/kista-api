// 앱셸 — 여러 모듈을 집계하는 진짜 앱 레벨 inbound 관심사만 담는다:
// enum 메타 번들(MetaController), 전역 예외→HTTP 매핑(GlobalExceptionHandler),
// NotifyPort.notifyError AOP 오류 로깅(ErrorLogAspect), 메트릭 설정(MetricsConfig),
// 3모듈 오케스트레이터(TradingCycleController).
// CLOSED이되 NamedInterface 0개 — 컨트롤러/@ControllerAdvice/@Aspect는 아무 모듈도 참조하지 않으므로
// 모든 모듈 NamedInterface로 fan-out해도 순환에 참여 불가(sink 모듈).
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.CLOSED
)
package com.kista.web;
