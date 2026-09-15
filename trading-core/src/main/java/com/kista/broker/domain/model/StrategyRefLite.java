package com.kista.broker.domain.model;

import com.kista.sharedkernel.StrategyTicker;

import java.util.UUID;

// MockBrokerAdapter 전용 — 계좌+ticker → 전략 해석에 필요한 최소 필드만 담은 broker 소유 뷰
// (PlacedOrderView/PositionView와 동일 패턴). Strategy 전체가 아닌 id/ticker만 노출한다.
public record StrategyRefLite(UUID id, StrategyTicker ticker) {}
