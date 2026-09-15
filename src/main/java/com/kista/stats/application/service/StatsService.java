package com.kista.stats.application.service;

import com.kista.sharedkernel.TimeZones;
import com.kista.stats.domain.model.*;
import com.kista.stats.application.usecase.UserStatsUseCase;
import com.kista.stats.application.port.output.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
class StatsService implements UserStatsUseCase {

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
    private final StatsResultCache statsResultCache;
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
        LocalDate effectiveTo = completedMonthEnd(to, BenchmarkGranularity.WEEKLY);
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
        // ETF는 별도 최상위 검증이 없어 investmentPointsPort.fetch() 내부에서만 검증되던 것을
        // 병렬 실행 전 fast-fail 위해 여기로 앞당긴다 (검증 로직·예외 타입은 100% 동일하게 재사용)
        validateScopeAndRange(scope, strategyId, from, to);
        BenchmarkComparisonKey key = new BenchmarkComparisonKey(
                userId, BenchmarkAssetType.ETF, scope, strategyId, null, symbol.name(), from, to);
        return comparisonWithExchangeRate(key,
                () -> computeEtfComparisonBody(userId, scope, strategyId, symbol, from, to));
    }

    private HousingBenchmarkComparison computeEtfComparisonBody(
            UUID userId, BenchmarkScope scope, UUID strategyId, EtfBenchmarkSymbol symbol, LocalDate from, LocalDate to) {
        LocalDate effectiveTo = completedMonthEnd(to, BenchmarkGranularity.DAILY);
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

    // 캐시 hit면 병렬화 없이 환율만 후결합, miss면 본체(DB)와 환율(외부 HTTP)을 virtual thread로 병렬 조회.
    // 본체∥환율 2갈래 — 벤치마크 시세 범위가 사이클 DB 조회 결과에 의존해 3갈래 불가, 환율 스레드는 DB 미사용이라 커넥션 증가 없음.
    private HousingBenchmarkComparison comparisonWithExchangeRate(
            BenchmarkComparisonKey key, Supplier<HousingBenchmarkComparison> bodyLoader) {
        HousingBenchmarkComparison cached = statsResultCache.peek(key);
        if (cached != null) {
            return cached.withCurrentExchangeRate(fetchCurrentExchangeRate());
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Object>> jobs = List.of(
                    () -> statsResultCache.getOrCompute(key, BENCHMARK_CACHE_TTL, bodyLoader),
                    () -> fetchCurrentExchangeRate());
            List<Future<Object>> futures = executor.invokeAll(jobs);
            HousingBenchmarkComparison body = (HousingBenchmarkComparison) join(futures.get(0));
            CurrentExchangeRate rate = (CurrentExchangeRate) join(futures.get(1));
            return body.withCurrentExchangeRate(rate);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("벤치마크 비교 병렬 조회가 중단됐습니다", e);
        }
    }

    // Future 결과를 언랩 — 원인이 RuntimeException이면 그대로(래핑 없이) 재전파
    private static Object join(Future<Object> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("벤치마크 비교 병렬 조회 실패", e.getCause());
        }
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

    // MONTHLY만 월 단위로 늦게 발행되는 데이터를 전제로 직전 완료 월까지 clamp한다.
    // 그 외(WEEKLY·DAILY)는 포인트 단위로 자주 갱신되어 clamp가 필요 없다 —
    // 그대로 적용하면 당월 투자 기록·벤치마크 시세가 전부 잘려나간다.
    private static LocalDate completedMonthEnd(LocalDate requestedTo, BenchmarkGranularity granularity) {
        LocalDate today = LocalDate.now(TimeZones.KST);
        if (granularity != BenchmarkGranularity.MONTHLY) {
            return requestedTo != null ? requestedTo : today;
        }
        YearMonth requestedMonth = YearMonth.from(requestedTo != null ? requestedTo : today);
        YearMonth lastCompletedMonth = YearMonth.from(today).minusMonths(1);
        YearMonth effectiveMonth = requestedMonth.isAfter(lastCompletedMonth)
                ? lastCompletedMonth : requestedMonth;
        return effectiveMonth.atEndOfMonth();
    }

    private CurrentExchangeRate fetchCurrentExchangeRate() {
        try {
            BigDecimal midRate = currentExchangeRatePort.getMidRate();
            if (midRate == null || midRate.signum() <= 0) {
                return null;
            }
            return new CurrentExchangeRate(midRate, Instant.now(), "TOSS_INVEST");
        } catch (RuntimeException e) {
            // CurrentExchangeRateHttpAdapter가 실패를 이미 흡수해 null을 반환하므로 실전에서는
            // 이 catch에 도달하지 않는다 — 방어적으로만 남긴다(실제 진단 로그는 어댑터 쪽에 있음).
            log.warn("현재 USD/KRW 환율 조회 실패", e);
            return null;
        }
    }

}
