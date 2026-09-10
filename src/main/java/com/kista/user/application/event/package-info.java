// user 모듈의 공개 계약 일부 — NewUserRegisteredEvent/UserApprovedEvent/UserRejectedEvent/
// UserReappliedEvent/UserDeletedEvent. notify 등 타 모듈이 @TransactionalEventListener로 구독한다
// (CLOSED↔CLOSED 모듈 간 이벤트 교차, trading.application.event와 동일 패턴). "event" 이름으로 공개된다.
// ApprovalRequirementDisabledEvent는 admin이 발행하고 user 자신이 구독 — admin은 참조 0건인 순수
// downstream sink라 admin 소유로 두면 즉시 모듈 순환이 생기므로 이벤트 소유를 user로 뒤집었다
// (ApprovalPolicyPort 포트 역전과 동일한 방향 판단).
@org.springframework.modulith.NamedInterface("event")
package com.kista.user.application.event;
