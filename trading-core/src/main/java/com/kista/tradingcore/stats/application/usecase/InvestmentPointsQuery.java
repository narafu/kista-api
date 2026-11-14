package com.kista.tradingcore.stats.application.usecase;

import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.stats.domain.model.InvestmentPointsResult;

import java.time.LocalDate;
import java.util.UUID;

// api의 벤치마크 비교(BenchmarkService)가 HTTP로 소비하는 내부 조회 유스케이스 —
// TradingStatsInternalController가 이 인터페이스에 의존한다(구현체 InvestmentPointsQueryService)
public interface InvestmentPointsQuery {

    enum Scope { STRATEGY, PORTFOLIO }

    InvestmentPointsResult fetch(UUID userId, Scope scope, UUID strategyId, Currency currency,
                                 LocalDate from, LocalDate to);
}
