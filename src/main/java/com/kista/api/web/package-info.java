// 앱셸 — 여러 모듈을 집계하는 진짜 앱 레벨 inbound 관심사만 담는다:
// enum 메타 번들(MetaController, finance+shared.kernel 2모듈 fan-out),
// 500 오류 보고(ApiExceptionReporter — 공용 advice platform.web.ProblemDetailExceptionHandler가 넘긴 예외를
// AppErrorRaisedEvent로 발행해 admin 리스너에 위임, 예외→HTTP 매핑은 각 모듈 *ProblemDetails가 기여),
// 프로세스 고유 라우트 인가 규칙(RootSecurityPolicy — platform.security.SecurityRoutePolicy 구현),
// 스케쥴러 수동 트리거(AdminSchedulerController — kista-scheduler role 전용 게이팅이라 admin 소유 부적합).
// trading-core 쪽 대칭 앱셸은 com.kista.tradingcore.web.
// CLOSED이되 NamedInterface 0개 — 컨트롤러/@ControllerAdvice는 아무 모듈도 참조하지 않으므로
// 모든 모듈 NamedInterface로 fan-out해도 순환에 참여 불가(sink 모듈).
@org.springframework.modulith.ApplicationModule(
    id = "web",
    type = org.springframework.modulith.ApplicationModule.Type.CLOSED
)
package com.kista.api.web;
