package com.kista.trading.stats.application.usecase;

import com.kista.trading.stats.domain.model.BenchmarkGranularity;
import com.kista.trading.stats.domain.model.InvestmentPointsResult;

import java.time.LocalDate;
import java.util.UUID;

// api의 벤치마크 비교(StatsService)가 HTTP로 소비하는 내부 조회 유스케이스 —
// TradingStatsInternalController가 이 인터페이스에만 의존한다(구현체 InvestmentPointsQueryService
// 직접 의존 금지 — HexagonalArchitectureTest.inbound_adapters_must_not_depend_on_application_layer)
public interface InvestmentPointsQuery {

    enum Scope { STRATEGY, PORTFOLIO }

    InvestmentPointsResult fetch(UUID userId, Scope scope, UUID strategyId,
                                 LocalDate from, LocalDate to, BenchmarkGranularity granularity);
}
