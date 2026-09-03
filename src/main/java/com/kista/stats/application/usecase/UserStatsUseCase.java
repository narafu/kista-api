package com.kista.stats.application.usecase;

import com.kista.stats.domain.model.CyclePerformancePage;
import com.kista.stats.domain.model.BenchmarkScope;
import com.kista.stats.domain.model.EquityCurve;
import com.kista.stats.domain.model.EtfBenchmarkSymbol;
import com.kista.stats.domain.model.HousingBenchmarkComparison;
import com.kista.stats.domain.model.HousingBenchmarkPrice;
import com.kista.stats.domain.model.HousingBenchmarkRegion;
import com.kista.stats.domain.model.HousingPriceIndex;
import com.kista.stats.domain.model.IndexPrice;
import com.kista.stats.domain.model.StatsSummary;
import com.kista.domain.model.strategy.Strategy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface UserStatsUseCase {
    StatsSummary getSummary(UUID userId);

    // from/to null 허용 (null이면 전체/오늘)
    EquityCurve getEquityCurve(UUID userId, Strategy.Type type, LocalDate from, LocalDate to);

    // type null이면 전체
    CyclePerformancePage getCyclePerformances(UUID userId, Strategy.Type type, Instant cursor, int size);

    HousingBenchmarkComparison getHousingBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            String regionCode, LocalDate from, LocalDate to);

    // ETF(SPY/QQQ/QLD/IBIT/ETHA) 벤치마크 비교 — 계산 로직은 getHousingBenchmarkComparison과 공유
    HousingBenchmarkComparison getEtfBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            EtfBenchmarkSymbol symbol, LocalDate from, LocalDate to);

    // from/to null 허용 (null이면 전체 구간). regionCode null이면 서울 기본값. 투자 데이터와 무관한 원본 시계열
    List<HousingBenchmarkPrice> getHousingBenchmarkSeries(LocalDate from, LocalDate to, String regionCode);

    // from/to null 허용 (null이면 전체 구간). regionCode null이면 서울 기본값. 투자 데이터와 무관한 원본 주간 매매가격지수 시계열
    List<HousingPriceIndex> getHousingPriceIndexSeries(LocalDate from, LocalDate to, String regionCode);

    // from/to null 허용 (null이면 전체 구간). symbol은 필수. 투자 데이터와 무관한 원본 일별 종가 시계열
    List<IndexPrice> getEtfPriceSeries(LocalDate from, LocalDate to, EtfBenchmarkSymbol symbol);

    // 시계열 조회에 사용 가능한 지역 카탈로그 (KB Land 제공 지역 그대로, 하드코딩 아님)
    List<HousingBenchmarkRegion> getHousingBenchmarkRegions();
}
