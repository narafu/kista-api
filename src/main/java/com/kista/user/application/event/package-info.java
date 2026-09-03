// user 모듈의 공개 계약 일부 — NewUserRegisteredEvent/UserApprovedEvent/UserRejectedEvent/
// UserReappliedEvent/UserDeletedEvent. notify 등 타 모듈이 @TransactionalEventListener로 구독한다
// (CLOSED↔CLOSED 모듈 간 이벤트 교차, trading.application.event와 동일 패턴). "event" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.user.application.event;
