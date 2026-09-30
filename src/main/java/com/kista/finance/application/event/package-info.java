// finance 모듈의 공개 계약 일부 — FinanceRegistrationReminderDueEvent. notify 모듈이 @EventListener로 구독한다
// (CLOSED↔CLOSED 모듈 간 이벤트 교차, market/benchmark의 event 패키지와 동일 패턴). "event" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.finance.application.event;
