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

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final HousingBenchmarkPricePort housingBenchmarkPricePort;
    private final ExchangeRatePort exchangeRatePort;
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

        LocalDate effectiveTo = completedMonthEnd(to);
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

        List<MonthlyInvestmentPoint> investmentPoints = monthlyReturnCalculator.calculate(
                cycles, positions, effectiveFrom, effectiveTo);
        LocalDate benchmarkFrom = effectiveFrom.minusMonths(1).withDayOfMonth(1);
        LocalDate benchmarkTo = effectiveTo.withDayOfMonth(1);
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

        HousingBenchmarkComparison comparison = comparisonBuilder.build(
                scope, selectedStrategy, quintile,
                SEOUL_REGION_CODE, SEOUL_REGION_NAME, sourceUpdatedDate,
                investmentPoints, selectedBenchmarkPrices);
        return comparison.withCurrentExchangeRate(fetchCurrentExchangeRate());
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

    private static LocalDate completedMonthEnd(LocalDate requestedTo) {
        LocalDate today = LocalDate.now(TimeZones.KST);
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
