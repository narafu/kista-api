// matching 커널의 공개 계약 일부 — CycleOrderStrategy 계열 주문생성 로직 + PriceCapPolicy.
// domain.model과 함께 "kernel" 이름으로 병합 공개. 전부 Spring 비의존 순수 계산 클래스 —
// 빈 배선은 com.kista.trading.application.service.CycleStrategyBeanConfig가 담당(BacktestEngine은 직접 new).
@org.springframework.modulith.NamedInterface("kernel")
package com.kista.matching.domain.strategy;
