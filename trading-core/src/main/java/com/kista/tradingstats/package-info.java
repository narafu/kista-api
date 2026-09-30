// trading 통계·백테스트 모듈 — trading 핵심(주문/사이클 실행 이력)의 공개 계약(domain/port)만 읽는 조회 전용 소비자라 trading은 이 모듈을 모른다(단방향).
// "domain"(domain.model+domain.model.backtest)·"usecase"(application.usecase)·"port"(application.port.output) 3개 NamedInterface 공개, application.service·domain.backtest·adapter는 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.tradingstats;
