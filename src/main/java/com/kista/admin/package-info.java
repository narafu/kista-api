// admin 애그리게이트(관리자 조회·정정·재정렬·감사로그·앱오류로그 + 런타임 설정) 모듈 —
// domain.model·application.{usecase,port.output}만 공개 계약, application.service·adapter 전체 internal.
// notify 직접 호출 0건이라 event NamedInterface 없음. 스케쥴러 수동 트리거 컨트롤러(옛 AdminSchedulerController)는
// com.kista.web으로 이전됨(2-role 배포에서 kista-scheduler 전용이 되며 admin 소유일 이유가 없어짐) — admin은
// adapter NamedInterface도 없음.
// adapter/out/aop/ErrorLogAspect는 2026-09-10 com.kista.web에서 이관(옛 web 캐치올 정리) — 자체 포트
// (AppErrorLogPort)만 컴파일 의존, notify는 문자열 포인트컷이라 컴파일 의존은 없지만 admin→notify 런타임
// 의존은 실재함(정적 분석 사각지대) — 양방향 참조 0건인 현재는 무해, notify가 admin에 엣지를 만들면
// verify()가 못 잡는 순환이 될 수 있어 신규 notify↔admin 포트/이벤트 추가 시 이 파일을 함께 확인할 것.
@org.springframework.modulith.ApplicationModule
package com.kista.admin;
