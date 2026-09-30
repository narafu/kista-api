package com.kista.trading.stats.domain.model;

import com.kista.trading.domain.model.Strategy;

import java.time.LocalDate;
import java.util.List;

// api의 벤치마크 비교(HousingBenchmarkComparisonBuilder)가 필요로 하는 투자 성과 시리즈 —
// STRATEGY scope일 때만 selectedStrategy가 채워진다. 순수 도메인 값 객체(adapter 비의존) —
// 내부 API 응답(contract.stats.InvestmentPointsResponse)으로의 매핑은 StatsContractMapper가 담당한다.
public record InvestmentPointsResult(
        List<InvestmentPoint> points,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Strategy selectedStrategy
) {}
