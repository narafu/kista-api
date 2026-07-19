package com.kista.domain.port.in;

import com.kista.domain.model.stats.CyclePerformancePage;
import com.kista.domain.model.stats.BenchmarkScope;
import com.kista.domain.model.stats.EquityCurve;
import com.kista.domain.model.stats.HousingBenchmarkComparison;
import com.kista.domain.model.stats.HousingBenchmarkPrice;
import com.kista.domain.model.stats.HousingBenchmarkRegion;
import com.kista.domain.model.stats.StatsSummary;
import com.kista.domain.model.strategy.Strategy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface UserStatsUseCase {
    StatsSummary getSummary(UUID userId);

    // from/to null 허용 (null이면 전체/오늘)
    EquityCurve getEquityCurve(UUID userId, LocalDate from, LocalDate to);

    // type null이면 전체
    CyclePerformancePage getCyclePerformances(UUID userId, Strategy.Type type, Instant cursor, int size);

    HousingBenchmarkComparison getHousingBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            int quintile, LocalDate from, LocalDate to);

    // from/to null 허용 (null이면 전체 구간). regionCode null이면 서울 기본값. 투자 데이터와 무관한 원본 시계열
    List<HousingBenchmarkPrice> getHousingBenchmarkSeries(LocalDate from, LocalDate to, String regionCode);

    // 시계열 조회에 사용 가능한 지역 카탈로그 (KB Land 제공 지역 그대로, 하드코딩 아님)
    List<HousingBenchmarkRegion> getHousingBenchmarkRegions();
}
