// account 모듈의 공개 계약 일부 — 불변 값 객체(Account/Command). "domain" 이름으로 공개된다.
// AccountNumberMasker는 com.kista.sharedkernel로 이관됨(순수 JDK 유틸이라 모듈 경계용 own-type이 아님)
@org.springframework.modulith.NamedInterface("domain")
package com.kista.account.domain.model;
