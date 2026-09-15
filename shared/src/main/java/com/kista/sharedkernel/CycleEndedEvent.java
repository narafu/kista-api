package com.kista.sharedkernel;

import java.util.UUID;

// 관리자 수동 체결 보정으로 사이클이 종료됨 — 트랜잭션 커밋 후에만 발행됨 (사용자 알림용)
// notify 리스너가 재조회 없이 바로 소비할 수 있도록 Account/Strategy 대신 필요한 스칼라만 담는다
public record CycleEndedEvent(UUID userId, UUID accountId, String accountNickname,
                               StrategyType strategyType, StrategyTicker ticker, StrategyCycleSeedType cycleSeedType) {}
