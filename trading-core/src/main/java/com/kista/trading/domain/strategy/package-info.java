// trading 모듈의 공개 계약 일부 — 전략 생성 정책 리졸버(StrategyCreationResolver 계열). "domain" 이름으로 병합 공개된다.
// CycleOrderStrategy 계열 주문생성 로직 + PriceCapPolicy는 com.kista.matching.domain.strategy로 이전됨(2026-09-09).
@org.springframework.modulith.NamedInterface("domain")
package com.kista.trading.domain.strategy;
