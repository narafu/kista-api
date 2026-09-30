package com.kista.benchmark.domain.model;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// InvestmentPointsPort.Result.selectedStrategy와 HousingBenchmarkComparison.strategy가
// 공유하는 최소 전략 투영 — 벤치마크 비교 화면이 실제로 쓰는 id/type/ticker 3필드만 담는다.
// 내부 API 응답(contract.stats.InvestmentPointsResponse.StrategyRefDto)에서 InvestmentPointsHttpAdapter가 매핑한다.
public record StrategyRef(UUID id, StrategyType type, StrategyTicker ticker) {}
