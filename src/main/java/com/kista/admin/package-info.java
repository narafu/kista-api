// admin 애그리게이트(관리자 조회·정정·재정렬·감사로그·앱오류로그 + 런타임 설정) 모듈 —
// domain.model·application.{usecase,port.output}만 공개 계약, application.service·adapter 전체 internal.
// notify 직접 호출 0건이라 event NamedInterface 없음. AdminSchedulerController는 trading/stats "schedule"의
// 소비자지 생산자가 아니라 adapter NamedInterface도 없음.
@org.springframework.modulith.ApplicationModule
package com.kista.admin;
