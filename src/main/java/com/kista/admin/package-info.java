// admin 애그리게이트(관리자 조회·정정·재정렬·감사로그·앱오류로그 + 런타임 설정) 모듈 —
// domain.model·application.{usecase,port.output}만 공개 계약, application.service·adapter 전체 internal.
// admin은 다른 모듈로부터의 참조가 0건인 순수 downstream sink라 admin 소유 이벤트/포트를 신설하면 즉시 역방향
// 엣지가 생겨 순환이 된다(user↔admin 빈 순환 해소 시도로 실측 확인됨 — ApprovalRequirementDisabledEvent는
// user.application.event 소유로 정착, admin은 발행만 한다). notify 직접 호출 0건이라 event NamedInterface 없음.
// 스케쥴러 수동 트리거 컨트롤러(옛 AdminSchedulerController)는
// com.kista.web으로 이전됨(2-role 배포에서 kista-scheduler 전용이 되며 admin 소유일 이유가 없어짐) — admin은
// adapter NamedInterface도 없음.
// 오류 로그(app_error_logs) 저장 입구는 둘이고 같은 AppErrorLogPort로 수렴한다 — root 프로세스 내부 발행분은
// adapter/in/event/AppErrorRaisedListener(sharedkernel AppErrorRaisedEvent 구독), trading-core 발행분은
// adapter/in/redis/AppErrorStreamConsumer. 옛 adapter/out/aop/ErrorLogAspect(NotifyPort.notifyError 문자열 포인트컷 —
// 정적 분석 사각지대의 admin→notify 런타임 결합)는 2026-09-30 이벤트 경로로 대체돼 삭제됐다(@Aspect는
// HexagonalArchitectureTest.no_aspects_in_codebase가 저장소 전체에서 금지).
@org.springframework.modulith.ApplicationModule
package com.kista.admin;
