package com.kista.user.application.port.output;

// 가입 승인 필요 여부를 잠금 조회로 확인하는 포트 — UserService.register()/reapply()가 관리자의
// 승인설정 전환(RuntimeSettingsService.updateSettings)과 직렬화하기 위해 FOR UPDATE 락이 필요하다.
// user 모듈이 admin의 RuntimeSettingsPort를 직접 참조하지 않도록, user가 필요한 만큼만 담은
// 전용 포트를 자체 정의하고 admin이 구현한다(broker MockSimulationDataPort와 동일한 포트 역전 패턴).
public interface ApprovalPolicyPort {
    boolean approvalRequiredForUpdate(); // FOR UPDATE 락 조회 — 동시 승인설정 전환과 직렬화 필수
}
