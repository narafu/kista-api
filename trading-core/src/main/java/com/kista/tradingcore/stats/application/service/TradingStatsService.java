package com.kista.tradingcore.stats.application.service;

import com.kista.shared.platform.cache.TtlResultCache;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.stats.domain.model.*;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.stats.application.usecase.TradingStatsUseCase;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.shared.kernel.Market;
import com.kista.tradingcore.trading.application.event.TradingReportReadyEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;

// 사용자 통계 중 trading 소유 부분 — 실현·미실현 손익 요약/누적 자산 곡선/사이클 성과 목록.
// housing/ETF 벤치마크 비교는 api의 BenchmarkService(com.kista.api.benchmark)가 계속 소유한다.
@Service
@RequiredArgsConstructor
class TradingStatsService implements TradingStatsUseCase {

    // 매매 리포트(체결·포지션 스냅샷 저장) 직후엔 onTradingReportReady가 사용자 키를 비우고, 그 외 변경은 TTL 만료에 맡긴다
    private static final Duration CURVE_CACHE_TTL = Duration.ofMinutes(5);

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort;
    private final CycleOrderStrategies cycleOrderStrategies; // 전략별 시드 점유 여부(occupiesSeed)로 원금 계산 분기
    private final TtlResultCache statsResultCache = new TtlResultCache(); // summary/equity-curve 결과 캐시

    // getSummary 캐시 키
    private record SummaryKey(UUID userId) {}

    // getEquityCurve 캐시 키 — 파라미터 조합별로 분리
    private record EquityCurveKey(UUID userId, StrategyType type, LocalDate from, LocalDate to) {}

    // 사이클 + 소속 전략 조인 뷰. netInvested = 시드 비점유 전략(DCA)의 일자별 누적 순투입액(시드 점유 전략은 null)
    private record CycleView(StrategyCycle cycle, Strategy strategy, NavigableMap<LocalDate, BigDecimal> netInvested) {
        boolean closed() {
            return cycle.endAmount() != null && cycle.endDate() != null;
        }

        // date 시점 원금 — 시드 점유 전략은 시작 금액, 비점유(DCA)는 초기 투입 원가(startAmount) + 그날까지의 누적 순투입액
        BigDecimal principalOn(LocalDate date) {
            if (netInvested == null) return cycle.startAmount();
            var entry = netInvested.floorEntry(date);
            return entry == null ? cycle.startAmount() : cycle.startAmount().add(entry.getValue());
        }

        // 현재(진행 중) 또는 종료 시점 원금
        BigDecimal principal() {
            return closed() ? principalOn(cycle.endDate()) : principalOn(LocalDate.MAX);
        }

        BigDecimal realizedPnl() {
            return cycle.endAmount().subtract(principal());
        }
    }

    // 매매 리포트 발행 = 체결·포지션 스냅샷 저장 완료 — 그 사용자의 summary/equity-curve 캐시만 비운다.
    // 체결 0건이어도 스냅샷이 쌓여 곡선이 바뀌므로 무조건 비운다. 발행처(TradingReporter)가 트랜잭션 밖이라 동기 @EventListener로 충분하고,
    // HIGHEST_PRECEDENCE로 TradingReportNotifier(SSE 발행)보다 먼저 실행해 UI 재조회가 stale 캐시를 받지 않게 한다
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void onTradingReportReady(TradingReportReadyEvent event) {
        UUID userId = event.userId();
        statsResultCache.invalidateIf(key -> key instanceof SummaryKey(UUID u) && u.equals(userId)
                || key instanceof EquityCurveKey k && k.userId().equals(userId));
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
                .filter(v -> !v.closed()).map(CycleView::principal));

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
    public CyclePerformancePage getCyclePerformances(UUID userId, StrategyType type, UUID accountId,
                                                     StrategyTicker ticker, CycleStatusFilter status, int page, int size) {
        // loadCycles가 본인 계좌로 한정하므로 타 사용자 accountId는 자연히 빈 결과
        List<CycleView> filtered = loadCycles(userId, false).stream()
                .filter(v -> type == null || v.strategy().type() == type)
                .filter(v -> accountId == null || v.strategy().accountId().equals(accountId))
                .filter(v -> ticker == null || v.strategy().ticker() == ticker)
                .filter(v -> status == null || status.matches(v.closed()))
                // createdAt 동률이면 id로 순서 고정 — 페이지 경계에서 항목이 흔들리지 않게
                .sorted(Comparator.comparing((CycleView v) -> v.cycle().createdAt())
                        .thenComparing(v -> v.cycle().id()).reversed())
                .toList();

        int totalPages = (int) Math.ceil(filtered.size() / (double) size);
        // 범위를 벗어난 page는 빈 목록
        int from = (int) Math.min((long) page * size, filtered.size());
        List<CycleView> pageItems = filtered.subList(from, Math.min(from + size, filtered.size()));
        // 미종료 사이클의 최신 포지션을 일괄 조회 (N+1 방지)
        Set<UUID> openCycleIds = pageItems.stream().filter(v -> !v.closed())
                .map(v -> v.cycle().id()).collect(Collectors.toSet());
        Map<UUID, CyclePosition> latestPositions = openCycleIds.isEmpty()
                ? Map.of() : cyclePositionPort.findLatestByCycleIds(openCycleIds);
        List<CyclePerformance> items = pageItems.stream().map(v -> toPerformance(v, latestPositions)).toList();
        return new CyclePerformancePage(items, page, filtered.size(), totalPages);
    }

    // ── private 헬퍼 ─────────────────────────────────────────────────────────

    // aggregate=true: 누적자산추이·전략유형비교처럼 사용자 전체를 합산하는 조회 — 모의계좌(MOCK)와 크립토(KRW) 계좌 제외.
    //   합산 금액에 통화 구분이 없어(주식 USD + 코인 KRW) 섞이면 의미가 깨지므로 크립토 계좌는 합산 경로에서만 뺀다(후속: 통화별 분리 집계).
    // 사이클 성과 목록은 사이클별 행(계좌 단일 통화)이고 이력 확인이 목적이라 모의·크립토도 포함(aggregate=false)한다.
    private List<CycleView> loadCycles(UUID userId, boolean aggregate) {
        List<UUID> accountIds = accountPort.findByUserId(userId).stream()
                .filter(a -> !aggregate || (a.broker() != Broker.MOCK && a.broker().getMarket() != Market.CRYPTO_KRW))
                .map(Account::id)
                .toList();
        if (accountIds.isEmpty()) return List.of();
        Map<UUID, Strategy> strategies = strategyPort.findByAccountIds(accountIds).values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(Strategy::id, Function.identity()));
        if (strategies.isEmpty()) return List.of();
        List<StrategyCycle> cycles = strategyCyclePort.findByStrategyIds(strategies.keySet());
        // 시드 비점유 전략 사이클만 모아 순투입액을 한 번에 조회 (N+1 방지)
        Set<UUID> seedlessCycleIds = cycles.stream()
                .filter(c -> !cycleOrderStrategies.of(strategies.get(c.strategyId()).type()).occupiesSeed())
                .map(StrategyCycle::id)
                .collect(Collectors.toSet());
        Map<UUID, NavigableMap<LocalDate, BigDecimal>> netInvested = seedlessCycleIds.isEmpty()
                ? Map.of() : orderPort.findFilledNetInvestedByCycleIds(seedlessCycleIds);
        return cycles.stream()
                .map(c -> new CycleView(c, strategies.get(c.strategyId()),
                        seedlessCycleIds.contains(c.id()) ? netInvested.getOrDefault(c.id(), new TreeMap<>()) : null))
                .toList();
    }

    // 진행 중 사이클의 미실현 = 최신 스냅샷 자산 - 개장금액 (스냅샷 없으면 제외)
    private Map<UUID, BigDecimal> unrealizedByCycle(List<CycleView> cycles) {
        List<CycleView> open = cycles.stream().filter(v -> !v.closed()).toList();
        Set<UUID> openCycleIds = open.stream().map(v -> v.cycle().id()).collect(Collectors.toSet());
        Map<UUID, CyclePosition> latestPositions = openCycleIds.isEmpty()
                ? Map.of() : cyclePositionPort.findLatestByCycleIds(openCycleIds);
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (CycleView v : open) {
            CyclePosition pos = latestPositions.get(v.cycle().id());
            if (pos != null) {
                result.put(v.cycle().id(), pos.totalAssetWithAvgPriceFallback().subtract(v.principal()));
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
            // 원금이 0인 사이클(VR 적립식 등)은 수익률 계산에서 제외한다.
            List<CycleView> returnable = closed.stream()
                    .filter(v -> v.principal().signum() != 0)
                    .toList();
            if (!returnable.isEmpty()) {
                avgReturnRate = returnable.stream()
                        .map(v -> v.realizedPnl().divide(v.principal(), 6, RoundingMode.HALF_UP))
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
            byDate.computeIfAbsent(date, _ -> new HashMap<>()).put(pos.strategyCycleId(), pos);
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
                asset = asset.add(posEntry.getValue().totalAssetWithAvgPriceFallback());
                principal = principal.add(view.principalOn(date));
            }
            points.add(new EquityPoint(date,
                    asset.setScale(2, RoundingMode.HALF_UP),
                    principal.setScale(2, RoundingMode.HALF_UP)));
        }
        return points;
    }

    private CyclePerformance toPerformance(CycleView v, Map<UUID, CyclePosition> latestPositions) {
        StrategyCycle c = v.cycle();
        BigDecimal endAmount = v.closed() ? c.endAmount()
                : Optional.ofNullable(latestPositions.get(c.id())).map(CyclePosition::totalAssetWithAvgPriceFallback).orElse(null);
        BigDecimal principal = v.principal(); // 시드 점유 전략은 startAmount, DCA는 초기 원가 + 누적 순투입액
        BigDecimal pnl = endAmount != null ? endAmount.subtract(principal) : null;
        // 원금이 0인 사이클(VR 적립식 등)은 수익률이 정의되지 않는다.
        BigDecimal returnRate = (pnl != null && principal.signum() != 0)
                ? pnl.divide(principal, 4, RoundingMode.HALF_UP) : null;
        LocalDate durationEnd = v.closed() ? c.endDate() : LocalDate.now(TimeZones.KST);
        // 시작예정일이 미래인 사이클은 아직 경과일이 없다 — 음수 대신 0
        int durationDays = (int) Math.max(0, ChronoUnit.DAYS.between(c.startDate(), durationEnd));
        return new CyclePerformance(c.id(), v.strategy().accountId(), v.strategy().type(), v.strategy().ticker(),
                c.startDate(), c.endDate(), principal, endAmount, pnl, returnRate,
                durationDays, v.closed());
    }

    private static BigDecimal sum(java.util.stream.Stream<BigDecimal> stream) {
        return stream.reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }
}
