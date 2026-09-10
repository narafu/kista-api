// 앱셸 — 여러 모듈을 집계하는 진짜 앱 레벨 inbound 관심사만 담는다:
// enum 메타 번들(MetaController, finance+matching+sharedkernel 3모듈 fan-out),
// 전역 예외→HTTP 매핑(GlobalExceptionHandler, account/broker/finance/user/trading/privacy 7모듈+ fan-out),
// 스케쥴러 수동 트리거(AdminSchedulerController — kista-scheduler role 전용 게이팅이라 admin 소유 부적합).
// ErrorLogAspect(단일 모듈 admin 소비)·MetricsConfig(모듈 의존 0)는 2026-09-10 캐치올 정리로 각각
// com.kista.admin.adapter.out.aop / com.kista.platform.metrics로 이전 — 진짜 다중 모듈 fan-out만 남음.
// CLOSED이되 NamedInterface 0개 — 컨트롤러/@ControllerAdvice/@Aspect는 아무 모듈도 참조하지 않으므로
// 모든 모듈 NamedInterface로 fan-out해도 순환에 참여 불가(sink 모듈).
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.CLOSED
)
package com.kista.web;
