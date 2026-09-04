// admin 애그리게이트(관리자 조회·정정·재정렬·감사로그·앱오류로그 + 런타임 설정) 모듈 —
// domain.model·application.{usecase,port.output}만 공개 계약, application.service·adapter 전체 internal.
// notify 직접 호출 0건이라 event NamedInterface 없음. 스케쥴러 수동 트리거 컨트롤러(옛 AdminSchedulerController)는
// com.kista.web으로 이전됨(2-role 배포에서 kista-scheduler 전용이 되며 admin 소유일 이유가 없어짐) — admin은
// adapter NamedInterface도 없음.
@org.springframework.modulith.ApplicationModule
package com.kista.admin;
