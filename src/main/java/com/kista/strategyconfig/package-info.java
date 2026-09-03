// strategy-config 애그리게이트(계좌별 영속 전략 설정) 모듈 —
// domain.model·application.{usecase,port.output}만 공개 계약, application.service·adapter 전체 internal.
// notify 직접 호출 0건·스케쥴러 없음이라 event/schedule NamedInterface 없음(admin과 동일 사유).
@org.springframework.modulith.ApplicationModule
package com.kista.strategyconfig;
