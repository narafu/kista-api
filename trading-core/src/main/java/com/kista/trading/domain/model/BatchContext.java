package com.kista.trading.domain.model;

// 스케쥴러에서 사이클별 실행에 필요한 컨텍스트 묶음
// strategy: 전략 설정 / currentCycle: 현재 StrategyCycle (initialUsdDeposit 보유) / account: Account 애그리게이트가 아닌 배치 경로용 투영
public record BatchContext(Strategy strategy, StrategyCycle currentCycle, TradingAccount account, TradingUserProfile userProfile) {}
