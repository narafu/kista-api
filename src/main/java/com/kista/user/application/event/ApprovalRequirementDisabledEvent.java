package com.kista.user.application.event;

// 승인 설정이 true→false로 전환된 직후 admin이 발행 — user 모듈이 구독해 PENDING 사용자를 일괄 승인한다.
// (admin↔user 빈 순환 해소: RuntimeSettingsService가 UserUseCase를 직접 호출하지 않는다. 이벤트를 admin 소유로
// 두면 admin은 참조 0건인 순수 downstream sink라 즉시 모듈 순환이 생겨 user 소유로 둔다 — ApprovalPolicyPort와
// 동일한 방향)
public record ApprovalRequirementDisabledEvent() {}
