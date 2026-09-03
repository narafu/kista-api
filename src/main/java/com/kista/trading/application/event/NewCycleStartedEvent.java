package com.kista.trading.application.event;

import com.kista.trading.domain.model.StrategyRef;

import java.math.BigDecimal;
import java.util.UUID;

// 새 사이클 시작 이벤트 — 발행처 트랜잭션 유무와 무관하게 리스너에서 알림 채널 라우팅 처리
public record NewCycleStartedEvent(UUID userId, UUID accountId, StrategyRef strategy, BigDecimal initialUsdDeposit) {}
