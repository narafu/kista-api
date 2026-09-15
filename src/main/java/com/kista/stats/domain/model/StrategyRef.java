package com.kista.stats.domain.model;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// InvestmentPointsPort.Result.selectedStrategy와 HousingBenchmarkComparison.strategy가
// 공유하는 최소 전략 투영 — trading.domain.model.Strategy 전체(6필드) 중 벤치마크 비교 화면이
// 실제로 쓰는 3필드만 담는다. 서버(InvestmentPointsResponse)는 Strategy 전체를 그대로 반환하지만
// Jackson의 FAIL_ON_UNKNOWN_PROPERTIES 기본값(false)이 초과 필드를 조용히 무시한다.
public record StrategyRef(UUID id, StrategyType type, StrategyTicker ticker) {}
