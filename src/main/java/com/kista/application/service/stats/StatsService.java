package com.kista.application.service.stats;

import com.kista.common.TimeZones;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
class StatsService implements UserStatsUseCase {

    private static final String SEOUL_REGION_CODE = "1100000000";
    private static final String SEOUL_REGION_NAME = "서울";
    // 실제 KB Land 데이터는 2008-12부터 존재 — 여유 있는 안전 하한
    private static final LocalDate EARLIEST_BENCHMARK_DATE = LocalDate.of(2000, 1, 1);

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final HousingBenchmarkPricePort housingBenchmarkPricePort;
    private final ExchangeRatePort exchangeRatePort;
    private final IndexPricePort indexPricePort;
    private final MonthlyReturnCalculator monthlyReturnCalculator = new MonthlyReturnCalculator();
    private final HousingBenchmarkComparisonBuilder comparisonBuilder =
            new HousingBenchmarkComparisonBuilder();

    // 사이클 + 소속 전략 조인 뷰
    private record CycleView(StrategyCycle cycle, Strategy strategy) {
        boolean closed() {
            return cycle.endAmount() != null && cycle.endDate() != null;
        }

        BigDecimal realizedPnl() {
            return cycle.endAmount().subtract(cycle.startAmount());
        }
    }

    @Override
    public StatsSummary getSummary(UUID userId) {
        List<CycleView> cycles = loadCycles(userId);
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
                .filter(v -> !v.closed()).map(v -> v.cycle().startAmount()));

        return new StatsSummary(totalRealized, totalUnrealized, activePrincipal, typeStats);
    }

    @Override
    public EquityCurve getEquityCurve(UUID userId, LocalDate from, LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(TimeZones.KST);
        // PAUSED 전략처럼 스냅샷 갱신이 멈춘 사이클의 carry-forward 상태를 보장하기 위해
        // 전체 범위 조회 (사용자당 스냅샷 수천 건 규모라 허용)
        Instant fromInstant = Instant.EPOCH;
        Instant toInstant = effectiveTo.plusDays(1).atStartOfDay(TimeZones.KST).toInstant(); // KST 자정 경계 — 04:30 배치 스냅샷이 해당 KST 일자에 속함

        List<CycleView> cycles = loadCycles(userId);
        List<CyclePosition> positions = cyclePositionPort.findByUserAndRange(userId, fromInstant, toInstant);
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
        List<CyclePerformance> items = pageItems.stream().map(this::toPerformance).toList();
        Instant nextCursor = hasMore ? pageItems.get(pageItems.size() - 1).cycle().createdAt() : null;
        return new CyclePerformancePage(items, nextCursor, hasMore);
    }

    @Override
    public HousingBenchmarkComparison getHousingBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            int quintile, LocalDate from, LocalDate to) {
        validateComparisonRequest(scope, strategyId, quintile, from, to);
        InvestmentContext ctx = buildInvestmentContext(userId, scope, strategyId, from, to, BenchmarkGranularity.MONTHLY);

        LocalDate benchmarkFrom = ctx.effectiveFrom().minusMonths(1).withDayOfMonth(1);
        LocalDate benchmarkTo = ctx.effectiveTo().withDayOfMonth(1);
        List<HousingBenchmarkPrice> benchmarkRows =
                housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                        HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE,
                        SEOUL_REGION_CODE, benchmarkFrom, benchmarkTo);
        Map<LocalDate, BigDecimal> selectedBenchmarkPrices = benchmarkRows.stream()
                .collect(Collectors.toMap(
                        HousingBenchmarkPrice::baseMonth,
                        price -> selectQuintilePrice(price, quintile),
                        (left, right) -> right,
                        TreeMap::new));
        LocalDate sourceUpdatedDate = benchmarkRows.stream()
                .map(HousingBenchmarkPrice::sourceUpdatedDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);

        HousingBenchmarkComparison.Benchmark benchmark = new HousingBenchmarkComparison.Benchmark(
                BenchmarkAssetType.HOUSING, SEOUL_REGION_CODE, SEOUL_REGION_NAME, quintile, null,
                SEOUL_REGION_NAME + " 아파트 " + quintile + "분위", sourceUpdatedDate);

        HousingBenchmarkComparison comparison = comparisonBuilder.build(
                scope, ctx.selectedStrategy(), benchmark, ctx.investmentPoints(), selectedBenchmarkPrices,
                BenchmarkGranularity.MONTHLY);
        return comparison.withCurrentExchangeRate(fetchCurrentExchangeRate());
    }

    @Override
    public HousingBenchmarkComparison getEtfBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            EtfBenchmarkSymbol symbol, LocalDate from, LocalDate to) {
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
                BenchmarkAssetType.ETF, null, null, null, symbol.name(),
                symbol.name() + " (" + symbol.description() + ")", sourceUpdatedDate);

        return comparisonBuilder.build(scope, ctx.selectedStrategy(), benchmark, ctx.investmentPoints(), prices,
                        BenchmarkGranularity.DAILY)
                .withCurrentExchangeRate(fetchCurrentExchangeRate());
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
            strategies = accountPort.findByUserId(userId).stream()
                    .flatMap(account -> strategyPort.findByAccountId(account.id()).stream())
                    .toList();
        }

        Set<UUID> strategyIds = strategies.stream().map(Strategy::id).collect(Collectors.toSet());
        List<StrategyCycle> cycles = strategyIds.isEmpty()
                ? List.of() : strategyCyclePort.findByStrategyIds(strategyIds);
        LocalDate effectiveFrom = from != null ? from.withDayOfMonth(1)
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
        return housingBenchmarkPricePort.findDistinctRegions(HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE);
    }

    // ── private 헬퍼 ─────────────────────────────────────────────────────────

    private List<CycleView> loadCycles(UUID userId) {
        Map<UUID, Strategy> strategies = accountPort.findByUserId(userId).stream()
                .flatMap(a -> strategyPort.findByAccountId(a.id()).stream())
                .collect(Collectors.toMap(Strategy::id, Function.identity()));
        if (strategies.isEmpty()) return List.of();
        return strategyCyclePort.findByStrategyIds(strategies.keySet()).stream()
                .map(c -> new CycleView(c, strategies.get(c.strategyId())))
                .toList();
    }

    private static void validateComparisonRequest(
            BenchmarkScope scope, UUID strategyId, int quintile, LocalDate from, LocalDate to) {
        if (scope == null) {
            throw new IllegalArgumentException("scope은 필수입니다");
        }
        if (scope == BenchmarkScope.STRATEGY && strategyId == null) {
            throw new IllegalArgumentException("STRATEGY scope에는 strategyId가 필요합니다");
        }
        if (scope == BenchmarkScope.PORTFOLIO && strategyId != null) {
            throw new IllegalArgumentException("PORTFOLIO scope에는 strategyId를 지정할 수 없습니다");
        }
        if (quintile < 1 || quintile > 5) {
            throw new IllegalArgumentException("quintile은 1부터 5까지여야 합니다");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to 이후일 수 없습니다");
        }
    }

    // 자산 종류와 무관한 공통 검증 (quintile 제외) — HOUSING은 validateComparisonRequest로 이미 검증된 뒤
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

    // 아파트(KB Land)는 월 단위로 늦게 발행되어 이번 달 데이터가 아직 없을 수 있으므로
    // 직전 완료 월까지만 비교한다. ETF는 매일 갱신되는 데이터라 이 clamp가 필요 없다 —
    // 그대로 적용하면 당월 투자 기록·ETF 시세가 전부 잘려나간다.
    private static LocalDate completedMonthEnd(LocalDate requestedTo, BenchmarkGranularity granularity) {
        LocalDate today = LocalDate.now(TimeZones.KST);
        if (granularity == BenchmarkGranularity.DAILY) {
            return requestedTo != null ? requestedTo : today;
        }
        YearMonth requestedMonth = YearMonth.from(requestedTo != null ? requestedTo : today);
        YearMonth lastCompletedMonth = YearMonth.from(today).minusMonths(1);
        YearMonth effectiveMonth = requestedMonth.isAfter(lastCompletedMonth)
                ? lastCompletedMonth : requestedMonth;
        return effectiveMonth.atEndOfMonth();
    }

    private static BigDecimal selectQuintilePrice(HousingBenchmarkPrice price, int quintile) {
        return switch (quintile) {
            case 1 -> price.firstQuintilePrice();
            case 2 -> price.secondQuintilePrice();
            case 3 -> price.thirdQuintilePrice();
            case 4 -> price.fourthQuintilePrice();
            case 5 -> price.fifthQuintilePrice();
            default -> throw new IllegalArgumentException("quintile은 1부터 5까지여야 합니다");
        };
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

    // 진행 중 사이클의 미실현 = 최신 스냅샷 자산 − startAmount (스냅샷 없으면 제외)
    private Map<UUID, BigDecimal> unrealizedByCycle(List<CycleView> cycles) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (CycleView v : cycles) {
            if (v.closed()) continue;
            cyclePositionPort.findLatestOne(v.cycle().id()).ifPresent(pos ->
                    result.put(v.cycle().id(), assetOf(pos).subtract(v.cycle().startAmount())));
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
            // startAmount=0인 사이클(VR 적립식 등)은 수익률 계산에서 제외 — 0으로 나눌 수 없음
            List<CycleView> returnable = closed.stream()
                    .filter(v -> v.cycle().startAmount().signum() != 0)
                    .toList();
            if (!returnable.isEmpty()) {
                avgReturnRate = returnable.stream()
                        .map(v -> v.realizedPnl().divide(v.cycle().startAmount(), 6, RoundingMode.HALF_UP))
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
                principal = principal.add(view.cycle().startAmount());
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

    private CyclePerformance toPerformance(CycleView v) {
        StrategyCycle c = v.cycle();
        BigDecimal endAmount = v.closed() ? c.endAmount()
                : cyclePositionPort.findLatestOne(c.id()).map(StatsService::assetOf).orElse(null);
        BigDecimal pnl = endAmount != null ? endAmount.subtract(c.startAmount()) : null;
        // startAmount=0인 사이클(VR 적립식 등)은 수익률이 정의되지 않음 — 0으로 나눌 수 없음
        BigDecimal returnRate = (pnl != null && c.startAmount().signum() != 0)
                ? pnl.divide(c.startAmount(), 4, RoundingMode.HALF_UP) : null;
        LocalDate durationEnd = v.closed() ? c.endDate() : LocalDate.now(TimeZones.KST);
        return new CyclePerformance(c.id(), v.strategy().type(), v.strategy().ticker(),
                c.startDate(), c.endDate(), c.startAmount(), endAmount, pnl, returnRate,
                (int) ChronoUnit.DAYS.between(c.startDate(), durationEnd), v.closed(), c.createdAt());
    }

    private static BigDecimal sum(java.util.stream.Stream<BigDecimal> stream) {
        return stream.reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }
}
