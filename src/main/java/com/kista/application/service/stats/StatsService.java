package com.kista.application.service.stats;

import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.stats.*;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.port.in.UserStatsUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.ExchangeRatePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
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
    // 사이클 스냅샷은 04:30 배치+체결 append-only라 TTL 만료로 충분(수동실행 직후 최대 5분 stale 허용 트레이드오프)
    private static final Duration CURVE_CACHE_TTL = Duration.ofMinutes(5);
    // 벤치마크 비교 본체(사이클·포지션·벤치마크 시세 DB 조회) 캐시 TTL — 환율은 캐시 제외, 응답마다 재조회
    private static final Duration BENCHMARK_CACHE_TTL = Duration.ofMinutes(10);

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final HousingBenchmarkPricePort housingBenchmarkPricePort;
    private final HousingPriceIndexPort housingPriceIndexPort;
    private final ExchangeRatePort exchangeRatePort;
    private final IndexPricePort indexPricePort;
    private final StatsResultCache statsResultCache;
    private final MonthlyReturnCalculator monthlyReturnCalculator = new MonthlyReturnCalculator();
    private final HousingBenchmarkComparisonBuilder comparisonBuilder =
            new HousingBenchmarkComparisonBuilder();

    // getSummary 캐시 키
    private record SummaryKey(UUID userId) {}

    // getEquityCurve 캐시 키 — 파라미터 조합별로 분리
    private record EquityCurveKey(UUID userId, Strategy.Type type, LocalDate from, LocalDate to) {}

    // 벤치마크 비교 캐시 키 — HOUSING(regionCode 사용, symbol=null) / ETF(symbol 사용, regionCode=null) 공용
    private record BenchmarkComparisonKey(
            UUID userId, BenchmarkAssetType assetType, BenchmarkScope scope, UUID strategyId,
            String regionCode, String symbol, LocalDate from, LocalDate to) {}

    // 사이클 + 소속 전략 조인 뷰
    private record CycleView(StrategyCycle cycle, Strategy strategy, BigDecimal effectiveStartAmount) {
        boolean closed() {
            return cycle.endAmount() != null && cycle.endDate() != null;
        }

        BigDecimal realizedPnl() {
            return cycle.endAmount().subtract(effectiveStartAmount);
        }
    }

    @Override
    public StatsSummary getSummary(UUID userId) {
        return statsResultCache.getOrCompute(
                new SummaryKey(userId), CURVE_CACHE_TTL, () -> computeSummary(userId));
    }

    private StatsSummary computeSummary(UUID userId) {
        List<CycleView> cycles = loadCycles(userId, true);
        Map<UUID, BigDecimal> unrealizedByCycle = unrealizedByCycle(cycles);

        Map<Strategy.Type, List<CycleView>> byType = cycles.stream()
                .collect(Collectors.groupingBy(v -> v.strategy().type(),
                        () -> new EnumMap<>(Strategy.Type.class), Collectors.toList()));

        List<StrategyTypeStats> typeStats = byType.entrySet().stream()
                .map(e -> toTypeStats(e.getKey(), e.getValue(), unrealizedByCycle))
                .toList();

        BigDecimal totalRealized = sum(typeStats.stream().map(StrategyTypeStats::realizedPnl));
        BigDecimal totalUnrealized = sum(typeStats.stream().map(StrategyTypeStats::unrealizedPnl));
        BigDecimal activePrincipal = sum(cycles.stream()
                .filter(v -> !v.closed()).map(CycleView::effectiveStartAmount));

        return new StatsSummary(totalRealized, totalUnrealized, activePrincipal, typeStats);
    }

    @Override
    public EquityCurve getEquityCurve(UUID userId, Strategy.Type type, LocalDate from, LocalDate to) {
        return statsResultCache.getOrCompute(
                new EquityCurveKey(userId, type, from, to), CURVE_CACHE_TTL,
                () -> computeEquityCurve(userId, type, from, to));
    }

    private EquityCurve computeEquityCurve(UUID userId, Strategy.Type type, LocalDate from, LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(TimeZones.KST);
        // PAUSED 전략처럼 스냅샷 갱신이 멈춘 사이클의 carry-forward 상태를 보장하기 위해
        // 전체 범위 조회 (사용자당 스냅샷 수천 건 규모라 허용)
        Instant fromInstant = Instant.EPOCH;
        Instant toInstant = effectiveTo.plusDays(1).atStartOfDay(TimeZones.KST).toInstant(); // KST 자정 경계 — 04:30 배치 스냅샷이 해당 KST 일자에 속함

        List<CycleView> cycles = loadCycles(userId, true).stream()
                .filter(v -> type == null || v.strategy().type() == type)
                .toList();
        Set<UUID> cycleIds = cycles.stream().map(v -> v.cycle().id()).collect(Collectors.toSet());
        // userId 스코프는 loadCycles(userId, true)가 이미 보장 — DB 조회 자체를 cycleIds로 좁혀 불필요한 타입 전체 조회 방지
        List<CyclePosition> positions = cyclePositionPort.findByCycleIdsAndRange(cycleIds, fromInstant, toInstant);
        List<EquityPoint> points = buildPoints(cycles, positions, from, effectiveTo);
        return new EquityCurve(points);
    }

    @Override
    public CyclePerformancePage getCyclePerformances(UUID userId, Strategy.Type type,
                                                     Instant cursor, int size) {
        List<CycleView> filtered = loadCycles(userId).stream()
                .filter(v -> type == null || v.strategy().type() == type)
                .sorted(Comparator.comparing((CycleView v) -> v.cycle().createdAt()).reversed())
                .filter(v -> cursor == null || v.cycle().createdAt().isBefore(cursor))
                .toList();

        boolean hasMore = filtered.size() > size;
        List<CycleView> pageItems = hasMore ? filtered.subList(0, size) : filtered;
        // 미종료 사이클의 최신 포지션을 일괄 조회 (N+1 방지)
        Set<UUID> openCycleIds = pageItems.stream().filter(v -> !v.closed())
                .map(v -> v.cycle().id()).collect(Collectors.toSet());
        Map<UUID, CyclePosition> latestPositions = openCycleIds.isEmpty()
                ? Map.of() : cyclePositionPort.findLatestByCycleIds(openCycleIds);
        List<CyclePerformance> items = pageItems.stream().map(v -> toPerformance(v, latestPositions)).toList();
        Instant nextCursor = hasMore ? pageItems.get(pageItems.size() - 1).cycle().createdAt() : null;
        return new CyclePerformancePage(items, nextCursor, hasMore);
    }

    @Override
    public HousingBenchmarkComparison getHousingBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            String regionCode, LocalDate from, LocalDate to) {
        validateComparisonRequest(scope, strategyId, regionCode, from, to);
        // 병렬 조회(본체∥환율) 이전에 소유권을 동기적으로 검증 — 인가 실패 시 외부 환율 API가 호출되지 않도록 보장
        authorizeIfStrategyScope(scope, strategyId, userId);
        BenchmarkComparisonKey key = new BenchmarkComparisonKey(
                userId, BenchmarkAssetType.HOUSING, scope, strategyId, regionCode, null, from, to);
        return comparisonWithExchangeRate(key,
                () -> computeHousingComparisonBody(userId, scope, strategyId, regionCode, from, to));
    }

    private HousingBenchmarkComparison computeHousingComparisonBody(
            UUID userId, BenchmarkScope scope, UUID strategyId, String regionCode, LocalDate from, LocalDate to) {
        InvestmentContext ctx = buildInvestmentContext(userId, scope, strategyId, from, to, BenchmarkGranularity.WEEKLY);

        List<HousingPriceIndex> indexRows = housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, regionCode,
                ctx.effectiveFrom(), ctx.effectiveTo());
        NavigableMap<LocalDate, BigDecimal> indexByDate = indexRows.stream()
                .collect(Collectors.toMap(HousingPriceIndex::baseDate, HousingPriceIndex::indexValue,
                        (left, right) -> right, TreeMap::new));

        // 투자 일별 지수를 KB 주간 조사일에 as-of(그 날짜 이하 최근값)로 스냅한다 — 조사일과
        // 투자 평가일이 어긋나는 날(미국 휴일, KB 결측 주)이 정확한 날짜 일치 교집합에서 조용히
        // 사라지는 것을 방지한다.
        NavigableMap<LocalDate, InvestmentPoint> investmentByDate = ctx.investmentPoints().stream()
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
        // ETF는 별도 최상위 검증이 없어 buildInvestmentContext 내부에서만 검증되던 것을
        // 병렬 실행 전 fast-fail 위해 여기로 앞당긴다 (검증 로직·예외 타입은 100% 동일하게 재사용)
        validateScopeAndRange(scope, strategyId, from, to);
        authorizeIfStrategyScope(scope, strategyId, userId);
        BenchmarkComparisonKey key = new BenchmarkComparisonKey(
                userId, BenchmarkAssetType.ETF, scope, strategyId, null, symbol.name(), from, to);
        return comparisonWithExchangeRate(key,
                () -> computeEtfComparisonBody(userId, scope, strategyId, symbol, from, to));
    }

    private HousingBenchmarkComparison computeEtfComparisonBody(
            UUID userId, BenchmarkScope scope, UUID strategyId, EtfBenchmarkSymbol symbol, LocalDate from, LocalDate to) {
        InvestmentContext ctx = buildInvestmentContext(userId, scope, strategyId, from, to, BenchmarkGranularity.DAILY);
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

        return comparisonBuilder.build(scope, ctx.selectedStrategy(), benchmark, ctx.investmentPoints(), prices,
                BenchmarkGranularity.DAILY);
    }

    // STRATEGY scope의 소유권을 병렬 조회 이전에 동기적으로 선검증 — 인가 실패가 병렬 잡(환율 등)을
    // 유발하지 않도록 fast-fail 한다. buildInvestmentContext도 동일 검증을 한 번 더(멱등) 수행한다.
    private void authorizeIfStrategyScope(BenchmarkScope scope, UUID strategyId, UUID userId) {
        if (scope != BenchmarkScope.STRATEGY) {
            return;
        }
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.findByIdOrThrow(strategy.accountId()).verifyOwnedBy(userId);
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

    // 자산 종류(HOUSING/ETF)와 무관한 공통 준비 단계 — 소유권 검증·사이클/포지션 조회·투자 지수 계산
    private record InvestmentContext(
            Strategy selectedStrategy, LocalDate effectiveFrom, LocalDate effectiveTo,
            List<InvestmentPoint> investmentPoints) {}

    private InvestmentContext buildInvestmentContext(
            UUID userId, BenchmarkScope scope, UUID strategyId, LocalDate from, LocalDate to,
            BenchmarkGranularity granularity) {
        validateScopeAndRange(scope, strategyId, from, to);

        LocalDate effectiveTo = completedMonthEnd(to, granularity);
        Strategy selectedStrategy = null;
        List<Strategy> strategies;
        if (scope == BenchmarkScope.STRATEGY) {
            selectedStrategy = strategyPort.findByIdOrThrow(strategyId);
            accountPort.findByIdOrThrow(selectedStrategy.accountId()).verifyOwnedBy(userId);
            strategies = List.of(selectedStrategy);
        } else {
            // 벤치마크 "전체 포트폴리오"는 실제 투자 성과 비교 목적이므로 모의계좌(MOCK)를 제외한다
            List<UUID> accountIds = accountPort.findByUserId(userId).stream()
                    .filter(account -> account.broker() != Account.Broker.MOCK)
                    .map(Account::id)
                    .toList();
            strategies = accountIds.isEmpty() ? List.of() : strategyPort.findByAccountIds(accountIds).values().stream()
                    .flatMap(List::stream)
                    .toList();
        }

        Set<UUID> strategyIds = strategies.stream().map(Strategy::id).collect(Collectors.toSet());
        List<StrategyCycle> cycles = strategyIds.isEmpty()
                ? List.of() : strategyCyclePort.findByStrategyIds(strategyIds);
        // MONTHLY만 월 단위 비교라 요청한 from을 월초로 내림한다. 그 외(WEEKLY·DAILY)는 포인트
        // 단위 비교라 사용자가 고른 정확한 날짜를 그대로 쓴다 — 월초로 내리면 요청하지 않은 기간까지 포함된다.
        LocalDate effectiveFrom = from != null
                ? (granularity != BenchmarkGranularity.MONTHLY ? from : from.withDayOfMonth(1))
                : cycles.stream().map(StrategyCycle::startDate).min(LocalDate::compareTo)
                        .orElse(effectiveTo).withDayOfMonth(1);
        Instant toInstant = effectiveTo.plusDays(1).atStartOfDay(TimeZones.KST).toInstant();
        List<CyclePosition> positions = scope == BenchmarkScope.STRATEGY
                ? cyclePositionPort.findByStrategyAndRange(strategyId, Instant.EPOCH, toInstant)
                : cyclePositionPort.findByUserAndRange(userId, Instant.EPOCH, toInstant);

        List<InvestmentPoint> investmentPoints = monthlyReturnCalculator.calculate(
                cycles, positions, effectiveFrom, effectiveTo, granularity);

        return new InvestmentContext(selectedStrategy, effectiveFrom, effectiveTo, investmentPoints);
    }

    @Override
    public List<HousingBenchmarkPrice> getHousingBenchmarkSeries(LocalDate from, LocalDate to, String regionCode) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to 이후일 수 없습니다");
        }
        LocalDate effectiveTo = to != null ? to : LocalDate.now(TimeZones.KST);
        LocalDate effectiveFrom = from != null ? from : EARLIEST_BENCHMARK_DATE;
        // 지역 미지정 시 서울 기본값 — KB Land 지역 카탈로그는 DB 동적 조회(getHousingBenchmarkRegions) 대상이라 하드코딩 enum 아님
        String effectiveRegionCode = (regionCode != null && !regionCode.isBlank()) ? regionCode : SEOUL_REGION_CODE;
        return housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE, effectiveRegionCode, effectiveFrom, effectiveTo);
    }

    @Override
    public List<HousingBenchmarkRegion> getHousingBenchmarkRegions() {
        return housingPriceIndexPort.findDistinctRegions(HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX);
    }

    // ── private 헬퍼 ─────────────────────────────────────────────────────────

    private List<CycleView> loadCycles(UUID userId) {
        return loadCycles(userId, false);
    }

    // excludeMock=true: 누적자산추이·전략유형비교처럼 실제 투자 성과 집계 목적인 조회에서 모의계좌(MOCK) 제외.
    // 사이클 성과 목록은 계좌별 이력 확인이 목적이라 모의계좌도 포함(excludeMock=false)한다.
    private List<CycleView> loadCycles(UUID userId, boolean excludeMock) {
        List<UUID> accountIds = accountPort.findByUserId(userId).stream()
                .filter(a -> !excludeMock || a.broker() != Account.Broker.MOCK)
                .map(Account::id)
                .toList();
        if (accountIds.isEmpty()) return List.of();
        Map<UUID, Strategy> strategies = strategyPort.findByAccountIds(accountIds).values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(Strategy::id, Function.identity()));
        if (strategies.isEmpty()) return List.of();
        List<StrategyCycle> cycles = strategyCyclePort.findByStrategyIds(strategies.keySet());
        // VR 전략 사이클의 개장 포지션을 일괄 조회 (N+1 방지)
        Set<UUID> vrCycleIds = cycles.stream()
                .filter(c -> strategies.get(c.strategyId()).isVr())
                .map(StrategyCycle::id)
                .collect(Collectors.toSet());
        Map<UUID, CyclePosition> openingPositions = vrCycleIds.isEmpty()
                ? Map.of() : cyclePositionPort.findFirstByCycleIds(vrCycleIds);
        return cycles.stream()
                .map(c -> toCycleView(c, strategies.get(c.strategyId()), openingPositions))
                .toList();
    }

    private CycleView toCycleView(StrategyCycle cycle, Strategy strategy, Map<UUID, CyclePosition> openingPositions) {
        BigDecimal effectiveStartAmount = cycle.startAmount();
        if (strategy.isVr()) {
            CyclePosition opening = openingPositions.get(cycle.id());
            effectiveStartAmount = opening != null
                    ? compatibleVrStartAmount(cycle, opening)
                    : cycle.startAmount();
        }
        return new CycleView(cycle, strategy, effectiveStartAmount);
    }

    // 종가 없는 양수 보유분은 개장 시장가를 복원할 수 없으므로 저장된 startAmount를 유지한다.
    private static BigDecimal compatibleVrStartAmount(StrategyCycle cycle, CyclePosition opening) {
        if (opening.holdings() > 0 && opening.closingPrice() == null) {
            return cycle.startAmount();
        }
        BigDecimal holdingsValue = opening.holdings() == 0
                ? BigDecimal.ZERO
                : opening.closingPrice().multiply(BigDecimal.valueOf(opening.holdings()));
        return opening.usdDeposit().add(holdingsValue).setScale(2, RoundingMode.HALF_UP);
    }

    private static void validateComparisonRequest(
            BenchmarkScope scope, UUID strategyId, String regionCode, LocalDate from, LocalDate to) {
        if (scope == null) {
            throw new IllegalArgumentException("scope은 필수입니다");
        }
        if (scope == BenchmarkScope.STRATEGY && strategyId == null) {
            throw new IllegalArgumentException("STRATEGY scope에는 strategyId가 필요합니다");
        }
        if (scope == BenchmarkScope.PORTFOLIO && strategyId != null) {
            throw new IllegalArgumentException("PORTFOLIO scope에는 strategyId를 지정할 수 없습니다");
        }
        if (regionCode == null || regionCode.isBlank()) {
            throw new IllegalArgumentException("regionCode는 비어있을 수 없습니다");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to 이후일 수 없습니다");
        }
    }

    // 자산 종류와 무관한 공통 검증 (regionCode 제외) — HOUSING은 validateComparisonRequest로 이미 검증된 뒤
    // buildInvestmentContext에서 한 번 더(멱등) 타고, ETF는 이 메서드가 유일한 검증 지점이다.
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
            TossExchangeRate rate = exchangeRatePort.getExchangeRate();
            if (rate == null || rate.midRate() == null || rate.midRate().signum() <= 0) {
                return null;
            }
            return new CurrentExchangeRate(rate.midRate(), Instant.now(), "TOSS_INVEST");
        } catch (RuntimeException e) {
            log.warn("현재 USD/KRW 환율 조회 실패", e);
            return null;
        }
    }

    // 진행 중 사이클의 미실현 = 최신 스냅샷 자산 - 호환 개장금액 (스냅샷 없으면 제외)
    private Map<UUID, BigDecimal> unrealizedByCycle(List<CycleView> cycles) {
        List<CycleView> open = cycles.stream().filter(v -> !v.closed()).toList();
        Set<UUID> openCycleIds = open.stream().map(v -> v.cycle().id()).collect(Collectors.toSet());
        Map<UUID, CyclePosition> latestPositions = openCycleIds.isEmpty()
                ? Map.of() : cyclePositionPort.findLatestByCycleIds(openCycleIds);
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (CycleView v : open) {
            CyclePosition pos = latestPositions.get(v.cycle().id());
            if (pos != null) {
                result.put(v.cycle().id(), assetOf(pos).subtract(v.effectiveStartAmount()));
            }
        }
        return result;
    }

    private StrategyTypeStats toTypeStats(Strategy.Type type, List<CycleView> views,
                                          Map<UUID, BigDecimal> unrealizedByCycle) {
        List<CycleView> closed = views.stream().filter(CycleView::closed).toList();
        List<CycleView> active = views.stream().filter(v -> !v.closed()).toList();

        BigDecimal realizedPnl = sum(closed.stream().map(CycleView::realizedPnl));
        BigDecimal unrealizedPnl = sum(active.stream()
                .map(v -> unrealizedByCycle.getOrDefault(v.cycle().id(), BigDecimal.ZERO)));

        BigDecimal winRate = null;
        BigDecimal avgReturnRate = null;
        BigDecimal avgDurationDays = null;
        if (!closed.isEmpty()) {
            long wins = closed.stream().filter(v -> v.realizedPnl().signum() > 0).count();
            winRate = BigDecimal.valueOf(wins)
                    .divide(BigDecimal.valueOf(closed.size()), 4, RoundingMode.HALF_UP);
            // 호환 개장금액이 0인 사이클(VR 적립식 등)은 수익률 계산에서 제외한다.
            List<CycleView> returnable = closed.stream()
                    .filter(v -> v.effectiveStartAmount().signum() != 0)
                    .toList();
            if (!returnable.isEmpty()) {
                avgReturnRate = returnable.stream()
                        .map(v -> v.realizedPnl().divide(v.effectiveStartAmount(), 6, RoundingMode.HALF_UP))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(returnable.size()), 4, RoundingMode.HALF_UP);
            }
            long totalDays = closed.stream()
                    .mapToLong(v -> ChronoUnit.DAYS.between(v.cycle().startDate(), v.cycle().endDate()))
                    .sum();
            avgDurationDays = BigDecimal.valueOf(totalDays)
                    .divide(BigDecimal.valueOf(closed.size()), 1, RoundingMode.HALF_UP);
        }
        return new StrategyTypeStats(type, closed.size(), active.size(),
                winRate, avgReturnRate, avgDurationDays, realizedPnl, unrealizedPnl);
    }

    // 날짜(KST)별 사이클 최신 스냅샷 carry-forward 합산.
    // 사이클 종료일 이후에는 해당 사이클을 자산·원금에서 제외한다.
    private List<EquityPoint> buildPoints(List<CycleView> cycles, List<CyclePosition> positions,
                                          LocalDate from, LocalDate to) {
        Map<UUID, CycleView> cycleById = cycles.stream()
                .collect(Collectors.toMap(v -> v.cycle().id(), Function.identity()));

        // positions는 created_at 오름차순 — 날짜별로 사이클당 마지막 스냅샷이 남는다
        TreeMap<LocalDate, Map<UUID, CyclePosition>> byDate = new TreeMap<>();
        for (CyclePosition pos : positions) {
            LocalDate date = pos.createdAt().atZone(TimeZones.KST).toLocalDate();
            byDate.computeIfAbsent(date, d -> new HashMap<>()).put(pos.strategyCycleId(), pos);
        }

        Map<UUID, CyclePosition> latest = new HashMap<>(); // carry-forward 상태
        List<EquityPoint> points = new ArrayList<>();
        for (var entry : byDate.entrySet()) {
            LocalDate date = entry.getKey();
            latest.putAll(entry.getValue());
            if (from != null && date.isBefore(from)) continue;
            if (date.isAfter(to)) break;

            BigDecimal asset = BigDecimal.ZERO;
            BigDecimal principal = BigDecimal.ZERO;
            for (var posEntry : latest.entrySet()) {
                CycleView view = cycleById.get(posEntry.getKey());
                if (view == null) continue;
                LocalDate endDate = view.cycle().endDate();
                if (endDate != null && date.isAfter(endDate)) continue; // 종료 사이클 탈락
                asset = asset.add(assetOf(posEntry.getValue()));
                principal = principal.add(view.effectiveStartAmount());
            }
            points.add(new EquityPoint(date,
                    asset.setScale(2, RoundingMode.HALF_UP),
                    principal.setScale(2, RoundingMode.HALF_UP)));
        }
        return points;
    }

    private static BigDecimal assetOf(CyclePosition pos) {
        BigDecimal unitPrice = pos.closingPrice() != null ? pos.closingPrice()
                : pos.avgPrice() != null ? pos.avgPrice() : BigDecimal.ZERO;
        return pos.usdDeposit().add(unitPrice.multiply(BigDecimal.valueOf(pos.holdings())))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private CyclePerformance toPerformance(CycleView v, Map<UUID, CyclePosition> latestPositions) {
        StrategyCycle c = v.cycle();
        BigDecimal endAmount = v.closed() ? c.endAmount()
                : Optional.ofNullable(latestPositions.get(c.id())).map(StatsService::assetOf).orElse(null);
        BigDecimal pnl = endAmount != null ? endAmount.subtract(v.effectiveStartAmount()) : null;
        // 호환 개장금액이 0인 사이클(VR 적립식 등)은 수익률이 정의되지 않는다.
        BigDecimal returnRate = (pnl != null && v.effectiveStartAmount().signum() != 0)
                ? pnl.divide(v.effectiveStartAmount(), 4, RoundingMode.HALF_UP) : null;
        LocalDate durationEnd = v.closed() ? c.endDate() : LocalDate.now(TimeZones.KST);
        return new CyclePerformance(c.id(), v.strategy().accountId(), v.strategy().type(), v.strategy().ticker(),
                c.startDate(), c.endDate(), v.effectiveStartAmount(), endAmount, pnl, returnRate,
                (int) ChronoUnit.DAYS.between(c.startDate(), durationEnd), v.closed(), c.createdAt());
    }

    private static BigDecimal sum(java.util.stream.Stream<BigDecimal> stream) {
        return stream.reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }
}
