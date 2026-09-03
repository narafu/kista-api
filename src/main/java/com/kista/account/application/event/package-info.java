// account 모듈의 공개 계약 일부 — 도메인 이벤트. "event" 이름으로 공개된다.
// AccountDeletedEvent — strategy-config가 구독해 cascade soft-delete를 수행한다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.account.application.event;
