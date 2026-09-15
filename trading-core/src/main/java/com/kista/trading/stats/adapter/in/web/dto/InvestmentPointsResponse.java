package com.kista.trading.stats.adapter.in.web.dto;

import com.kista.trading.domain.model.Strategy;
import com.kista.trading.stats.domain.model.InvestmentPoint;

import java.time.LocalDate;
import java.util.List;

// api의 벤치마크 비교(HousingBenchmarkComparisonBuilder)가 필요로 하는 투자 성과 시리즈 —
// STRATEGY scope일 때만 selectedStrategy가 채워진다
public record InvestmentPointsResponse(
        List<InvestmentPoint> points,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Strategy selectedStrategy
) {}
