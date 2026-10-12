package com.kista.benchmark.application.service;

import com.kista.sharedkernel.BenchmarkGranularity;
import com.kista.sharedkernel.TimeZones;
import com.kista.benchmark.domain.model.*;
import com.kista.benchmark.application.usecase.BenchmarkUseCase;
import com.kista.benchmark.application.port.output.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class BenchmarkService implements BenchmarkUseCase {

    private static final String SEOUL_REGION_CODE = "1100000000";
    // 실제 KB Land 데이터는 2008-12부터 존재 — 여유 있는 안전 하한
    private static final LocalDate EARLIEST_BENCHMARK_DATE = LocalDate.of(2000, 1, 1);
    // 벤치마크 비교 본체(사이클·포지션·벤치마크 시세 DB 조회) 캐시 TTL — 환율은 캐시 제외, 응답마다 재조회
    private static final Duration BENCHMARK_CACHE_TTL = Duration.ofMinutes(10);

    private final InvestmentPointsPort investmentPointsPort;
    private final HousingBenchmarkPricePort housingBenchmarkPricePort;
    private final HousingPriceIndexPort housingPriceIndexPort;
    private final CurrentExchangeRatePort currentExchangeRatePort;
    private final IndexPricePort indexPricePort;
    private final BenchmarkResultCache statsResultCache;
    private final HousingBenchmarkComparisonBuilder comparisonBuilder =
            new HousingBenchmarkComparisonBuilder();

    // 벤치마크 비교 캐시 키 — HOUSING(regionCode 사용, symbol=null) / ETF(symbol 사용, regionCode=null) 공용
    private record BenchmarkComparisonKey(
            UUID userId, BenchmarkAssetType assetType, BenchmarkScope scope, UUID strategyId,
            String regionCode, String symbol, LocalDate from, LocalDate to) {}

    @Override
    public HousingBenchmarkComparison getHousingBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            String regionCode, LocalDate from, LocalDate to) {
        validateComparisonRequest(scope, strategyId, regionCode, from, to);
        BenchmarkComparisonKey key = new BenchmarkComparisonKey(
                userId, BenchmarkAssetType.HOUSING, scope, strategyId, regionCode, null, from, to);
        return comparisonWithExchangeRate(key,
                () -> computeHousingComparisonBody(userId, scope, strategyId, regionCode, from, to));
    }

    private HousingBenchmarkComparison computeHousingComparisonBody(
            UUID userId, BenchmarkScope scope, UUID strategyId, String regionCode, LocalDate from, LocalDate to) {
        LocalDate effectiveTo = completedMonthEnd(to);
        InvestmentPointsPort.Result ctx = investmentPointsPort.fetch(
                userId, scope, strategyId, from, effectiveTo,
                BenchmarkGranularity.WEEKLY);

        List<HousingPriceIndex> indexRows = housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, regionCode,
                ctx.effectiveFrom(), ctx.effectiveTo());
        NavigableMap<LocalDate, BigDecimal> indexByDate = indexRows.stream()
                .collect(Collectors.toMap(HousingPriceIndex::baseDate, HousingPriceIndex::indexValue,
                        (left, right) -> right, TreeMap::new));

        // 투자 일별 지수를 KB 주간 조사일에 as-of(그 날짜 이하 최근값)로 스냅한다 — 조사일과
        // 투자 평가일이 어긋나는 날(미국 휴일, KB 결측 주)이 정확한 날짜 일치 교집합에서 조용히
        // 사라지는 것을 방지한다.
        NavigableMap<LocalDate, InvestmentPoint> investmentByDate = ctx.points().stream()
                .collect(Collectors.toMap(InvestmentPoint::baseDate, Function.identity(),
                        (left, right) -> right, TreeMap::new));
        List<InvestmentPoint> snappedPoints = new ArrayList<>();
        for (LocalDate surveyDate : indexByDate.keySet()) {
            // 투자 종료(마지막 스냅샷) 이후 조사일은 스킵 — floorEntry가 마지막 값을 그대로
            // 반환해 투자지수가 고정된 채 벤치마크만 계속 움직이는 착시를 방지한다.
            if (!investmentByDate.isEmpty() && surveyDate.isAfter(investmentByDate.lastKey())) continue;
            var asOf = investmentByDate.floorEntry(surveyDate);
            if (asOf == null) continue; // 투자 시작 전 조사일은 스킵
            snappedPoints.add(new InvestmentPoint(surveyDate, asOf.getValue().investmentIndexUsd(), null));
        }

        String regionName = indexRows.stream().findFirst().map(HousingPriceIndex::regionName).orElse(null);
        LocalDate sourceUpdatedDate = indexRows.stream()
                .map(HousingPriceIndex::sourceUpdatedDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);

        HousingBenchmarkComparison.Benchmark benchmark = new HousingBenchmarkComparison.Benchmark(
                BenchmarkAssetType.HOUSING, regionCode, regionName, null,
                (regionName != null ? regionName : regionCode) + " 아파트 매매가격지수", sourceUpdatedDate);

        return comparisonBuilder.build(
                scope, ctx.selectedStrategy(), benchmark, snappedPoints, indexByDate,
                BenchmarkGranularity.WEEKLY);
    }

    @Override
    public HousingBenchmarkComparison getEtfBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            EtfBenchmarkSymbol symbol, LocalDate from, LocalDate to) {
        // 데이터 조회 전 fast-fail — 검증 로직·예외 타입은 HOUSING과 동일하게 재사용
        validateScopeAndRange(scope, strategyId, from, to);
        BenchmarkComparisonKey key = new BenchmarkComparisonKey(
                userId, BenchmarkAssetType.ETF, scope, strategyId, null, symbol.name(), from, to);
        return comparisonWithExchangeRate(key,
                () -> computeEtfComparisonBody(userId, scope, strategyId, symbol, from, to));
    }

    private HousingBenchmarkComparison computeEtfComparisonBody(
            UUID userId, BenchmarkScope scope, UUID strategyId, EtfBenchmarkSymbol symbol, LocalDate from, LocalDate to) {
        LocalDate effectiveTo = completedMonthEnd(to);
        InvestmentPointsPort.Result ctx = investmentPointsPort.fetch(
                userId, scope, strategyId, from, effectiveTo,
                BenchmarkGranularity.DAILY);
        LocalDate benchmarkFrom = ctx.effectiveFrom().minusMonths(1).withDayOfMonth(1);
        LocalDate benchmarkTo = ctx.effectiveTo().withDayOfMonth(1);
        LocalDate dailyTo = benchmarkTo.plusMonths(1).minusDays(1);

        // ETF는 다운샘플링 없이 거래일별 종가를 그대로 벤치마크 가격으로 사용한다.
        // IndexPrice.tradeDate는 US 거래일 원본(KST 변환은 소비처 책임 — IndexPrice 문서 참고) —
        // KST 투자지수 날짜와 매칭하려면 +1일 보정이 필요하다. KST 거래일(정산 아침)은 항상 US 거래일
        // 다음날이라는 규칙은 UsTradeDates.toKstTradeDate와 동일하나, 그 클래스는 어댑터 전용이라
        // 여기서는 같은 규칙을 직접 적용한다.
        List<IndexPrice> dailyPrices = indexPricePort.findBySymbolAndRange(symbol.name(), benchmarkFrom, dailyTo);
        Map<LocalDate, BigDecimal> prices = dailyPrices.stream()
                .collect(Collectors.toMap(
                        price -> price.tradeDate().plusDays(1), IndexPrice::close, (left, right) -> right, TreeMap::new));
        LocalDate sourceUpdatedDate = dailyPrices.stream().map(IndexPrice::tradeDate)
                .max(LocalDate::compareTo).map(date -> date.plusDays(1)).orElse(null);

        HousingBenchmarkComparison.Benchmark benchmark = new HousingBenchmarkComparison.Benchmark(
                BenchmarkAssetType.ETF, null, null, symbol.name(),
                symbol.name() + " (" + symbol.description() + ")", sourceUpdatedDate);

        return comparisonBuilder.build(scope, ctx.selectedStrategy(), benchmark, ctx.points(), prices,
                BenchmarkGranularity.DAILY);
    }

    // 본체(캐시 대상)를 먼저 구한 뒤 환율을 후결합 — 본체 실패(소유권 등) 시 환율 조회를 하지 않는다
    private HousingBenchmarkComparison comparisonWithExchangeRate(
            BenchmarkComparisonKey key, Supplier<HousingBenchmarkComparison> bodyLoader) {
        HousingBenchmarkComparison body = statsResultCache.getOrCompute(key, BENCHMARK_CACHE_TTL, bodyLoader);
        return body.withCurrentExchangeRate(fetchCurrentExchangeRate());
    }

    @Override
    public List<HousingBenchmarkPrice> getHousingBenchmarkSeries(LocalDate from, LocalDate to, String regionCode) {
        EffectiveRange range = EffectiveRange.of(from, to);
        // 지역 미지정 시 서울 기본값 — KB Land 지역 카탈로그는 DB 동적 조회(getHousingBenchmarkRegions) 대상이라 하드코딩 enum 아님
        String effectiveRegionCode = (regionCode != null && !regionCode.isBlank()) ? regionCode : SEOUL_REGION_CODE;
        return housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE, effectiveRegionCode, range.from(), range.to());
    }

    @Override
    public List<HousingPriceIndex> getHousingPriceIndexSeries(LocalDate from, LocalDate to, String regionCode) {
        EffectiveRange range = EffectiveRange.of(from, to);
        // 지역 미지정 시 서울 기본값 — KB Land 지역 카탈로그는 DB 동적 조회(getHousingBenchmarkRegions) 대상이라 하드코딩 enum 아님
        String effectiveRegionCode = (regionCode != null && !regionCode.isBlank()) ? regionCode : SEOUL_REGION_CODE;
        return housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, effectiveRegionCode, range.from(), range.to());
    }

    @Override
    public List<IndexPrice> getEtfPriceSeries(LocalDate from, LocalDate to, EtfBenchmarkSymbol symbol) {
        EffectiveRange range = EffectiveRange.of(from, to);
        return indexPricePort.findBySymbolAndRange(symbol.name(), range.from(), range.to());
    }

    // from/to 널가드+기본값 적용 — from>to면 예외, 미지정 시 [EARLIEST_BENCHMARK_DATE, 오늘 KST]로 채운다
    private record EffectiveRange(LocalDate from, LocalDate to) {
        static EffectiveRange of(LocalDate from, LocalDate to) {
            if (from != null && to != null && from.isAfter(to)) {
                throw new IllegalArgumentException("from은 to 이후일 수 없습니다");
            }
            return new EffectiveRange(
                    from != null ? from : EARLIEST_BENCHMARK_DATE,
                    to != null ? to : LocalDate.now(TimeZones.KST));
        }
    }

    @Override
    public List<HousingBenchmarkRegion> getHousingBenchmarkRegions() {
        return housingPriceIndexPort.findDistinctRegions(HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX);
    }

    // ── private 헬퍼 ─────────────────────────────────────────────────────────

    private static void validateComparisonRequest(
            BenchmarkScope scope, UUID strategyId, String regionCode, LocalDate from, LocalDate to) {
        validateScopeAndRange(scope, strategyId, from, to);
        if (regionCode == null || regionCode.isBlank()) {
            throw new IllegalArgumentException("regionCode는 비어있을 수 없습니다");
        }
    }

    // 자산 종류와 무관한 공통 검증 (regionCode 제외) — HOUSING은 validateComparisonRequest로 이미 검증됐고,
    // ETF는 이 메서드가 유일한 검증 지점이다. STRATEGY scope 소유권 검증은 investmentPointsPort.fetch()
    // 내부(trading 쪽 InvestmentPointsQueryService)에서 수행된다 — 아래 "ownership-check timing" 참고.
    private static void validateScopeAndRange(
            BenchmarkScope scope, UUID strategyId, LocalDate from, LocalDate to) {
        if (scope == null) {
            throw new IllegalArgumentException("scope은 필수입니다");
        }
        if (scope == BenchmarkScope.STRATEGY && strategyId == null) {
            throw new IllegalArgumentException("STRATEGY scope에는 strategyId가 필요합니다");
        }
        if (scope == BenchmarkScope.PORTFOLIO && strategyId != null) {
            throw new IllegalArgumentException("PORTFOLIO scope에는 strategyId를 지정할 수 없습니다");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to 이후일 수 없습니다");
        }
    }

    // WEEKLY·DAILY는 포인트 단위로 자주 갱신되어 clamp가 필요 없다 — 요청값 그대로 사용
    // (root가 실제로 넘기는 granularity는 WEEKLY/DAILY뿐 — MONTHLY clamp 분기는 사용되지 않아 제거됨)
    private static LocalDate completedMonthEnd(LocalDate requestedTo) {
        return requestedTo != null ? requestedTo : LocalDate.now(TimeZones.KST);
    }

    // 조회 실패는 CurrentExchangeRateHttpAdapter가 null로 흡수한다
    private CurrentExchangeRate fetchCurrentExchangeRate() {
        BigDecimal midRate = currentExchangeRatePort.getMidRate();
        if (midRate == null || midRate.signum() <= 0) {
            return null;
        }
        return new CurrentExchangeRate(midRate, Instant.now(), "TOSS_INVEST");
    }

}
