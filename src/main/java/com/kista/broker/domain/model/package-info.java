// broker 모듈의 공개 계약 일부 — 공통 불변 값 객체(Currency/DailyTransaction* 등). domain.model.kis/toss와 함께 "domain" 이름으로 병합 공개된다. application.service는 별도 "application" 이름으로 공개.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.broker.domain.model;
