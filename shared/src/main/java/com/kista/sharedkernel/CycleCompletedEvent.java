package com.kista.sharedkernel;

import java.util.UUID;

// 사이클 종료(holdings=0) 이벤트 — 발행처 트랜잭션 유무와 무관하게 리스너에서 알림 채널 라우팅 처리
// notify 리스너가 재조회 없이 바로 소비할 수 있도록 Account/Strategy 대신 필요한 스칼라만 담는다
public record CycleCompletedEvent(UUID userId, UUID accountId, String accountNickname,
                                   StrategyType strategyType, StrategyTicker ticker, StrategyCycleSeedType cycleSeedType) {}
