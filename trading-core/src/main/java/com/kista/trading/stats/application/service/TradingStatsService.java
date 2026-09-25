package com.kista.trading.stats.application.service;

import com.kista.sharedkernel.TimeZones;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.trading.stats.domain.model.*;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.stats.application.usecase.TradingStatsUseCase;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.CyclePositionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.kista.sharedkernel.StrategyType;

// 사용자 통계 중 trading 소유 부분 — 실현·미실현 손익 요약/누적 자산 곡선/사이클 성과 목록.
// housing/ETF 벤치마크 비교는 api의 StatsService(com.kista.stats)가 계속 소유한다.
@Service
@RequiredArgsConstructor
class TradingStatsService implements TradingStatsUseCase {

    // 사이클 스냅샷은 04:30 배치+체결 append-only라 TTL 만료로 충분(수동실행 직후 최대 5분 stale 허용 트레이드오프)
    private static final Duration CURVE_CACHE_TTL = Duration.ofMinutes(5);

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final TradingStatsResultCache statsResultCache;

    // getSummary 캐시 키
    private record SummaryKey(UUID userId) {}

    // getEquityCurve 캐시 키 — 파라미터 조합별로 분리
    private record EquityCurveKey(UUID userId, StrategyType type, LocalDate from, LocalDate to) {}

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

        Map<StrategyType, List<CycleView>> byType = cycles.stream()
                .collect(Collectors.groupingBy(v -> v.strategy().type(),
                        () -> new EnumMap<>(StrategyType.class), Collectors.toList()));

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
    public EquityCurve getEquityCurve(UUID userId, StrategyType type, LocalDate from, LocalDate to) {
        return statsResultCache.getOrCompute(
                new EquityCurveKey(userId, type, from, to), CURVE_CACHE_TTL,
                () -> computeEquityCurve(userId, type, from, to));
    }

    private EquityCurve computeEquityCurve(UUID userId, StrategyType type, LocalDate from, LocalDate to) {
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
    public CyclePerformancePage getCyclePerformances(UUID userId, StrategyType type,
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

    // ── private 헬퍼 ─────────────────────────────────────────────────────────

    private List<CycleView> loadCycles(UUID userId) {
        return loadCycles(userId, false);
    }

    // excludeMock=true: 누적자산추이·전략유형비교처럼 실제 투자 성과 집계 목적인 조회에서 모의계좌(MOCK) 제외.
    // 사이클 성과 목록은 계좌별 이력 확인이 목적이라 모의계좌도 포함(excludeMock=false)한다.
    private List<CycleView> loadCycles(UUID userId, boolean excludeMock) {
        List<UUID> accountIds = accountPort.findByUserId(userId).stream()
                .filter(a -> !excludeMock || a.broker() != Broker.MOCK)
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
                result.put(v.cycle().id(), assetWithAvgPriceFallback(pos).subtract(v.effectiveStartAmount()));
            }
        }
        return result;
    }

    private StrategyTypeStats toTypeStats(StrategyType type, List<CycleView> views,
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
                asset = asset.add(assetWithAvgPriceFallback(posEntry.getValue()));
                principal = principal.add(view.effectiveStartAmount());
            }
            points.add(new EquityPoint(date,
                    asset.setScale(2, RoundingMode.HALF_UP),
                    principal.setScale(2, RoundingMode.HALF_UP)));
        }
        return points;
    }

    // 종가 없으면 매입평단가로 대체(최후 0) → MonthlyReturnCalculator와 달리 null을 반환하지 않고 항상 값 산출, scale=2 반올림
    private static BigDecimal assetWithAvgPriceFallback(CyclePosition pos) {
        BigDecimal unitPrice = pos.closingPrice() != null ? pos.closingPrice()
                : pos.avgPrice() != null ? pos.avgPrice() : BigDecimal.ZERO;
        return pos.usdDeposit().add(unitPrice.multiply(BigDecimal.valueOf(pos.holdings())))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private CyclePerformance toPerformance(CycleView v, Map<UUID, CyclePosition> latestPositions) {
        StrategyCycle c = v.cycle();
        BigDecimal endAmount = v.closed() ? c.endAmount()
                : Optional.ofNullable(latestPositions.get(c.id())).map(TradingStatsService::assetWithAvgPriceFallback).orElse(null);
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
