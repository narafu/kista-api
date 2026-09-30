// trading 실행 엔진(주문/사이클 실행 이력/주문생성 전략 계열) 모듈 — "domain"(domain.model+domain.strategy)·"usecase"(application.usecase)·"port"(application.port.output)·"schedule"(adapter.in.schedule)·"event"(application.event) 5개 NamedInterface 공개, application.service·adapter.out.*은 internal. 통계·백테스트는 tradingstats, 매매 알림은 tradingnotify 모듈로 분리됐다.
@org.springframework.modulith.ApplicationModule
package com.kista.trading;
