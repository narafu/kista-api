// trading 실행 엔진(주문/사이클 실행 이력/주문생성 전략 계열) 모듈 — "domain"(domain.model+domain.strategy)·"usecase"(application.usecase)·"port"(application.port.output)·"event"(application.event)·"schedule"(adapter.in.schedule) 5개 NamedInterface 공개, application.service·adapter.out.*은 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.trading;
