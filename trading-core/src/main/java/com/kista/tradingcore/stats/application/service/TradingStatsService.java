package com.kista.tradingcore.stats.application.service;

import com.kista.shared.platform.cache.TtlResultCache;
import com.kista.shared.kernel.KstDays;
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
import com.kista.tradingcore.trading.application.port.output.CycleCapitalEventPort;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.trading.application.event.StrategyCapitalChangedEvent;
import com.kista.tradingcore.trading.application.event.TradingReportReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;

// 사용자 통계 중 trading 소유 부분 — 실현·미실현 손익 요약/누적 자산 곡선/사이클 성과 목록.
// housing/ETF 벤치마크 비교는 api의 BenchmarkService(com.kista.api.benchmark)가 계속 소유한다.
@Service
class TradingStatsService implements TradingStatsUseCase {

    // 매매 리포트(체결·포지션 스냅샷 저장) 직후엔 onTradingReportReady가, 투자금·시드·VR 재설정·체결 보정·재개·삭제 직후엔 onStrategyCapitalChanged가 사용자 키를 비우고, 그 외 변경은 TTL 만료에 맡긴다
    private static final Duration CURVE_CACHE_TTL = Duration.ofMinutes(5);

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final CycleCapitalEventPort cycleCapitalEventPort; // 투자금 추가·인출 이력 — 과거 원금·자산·장부가 실현손익 복원용
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입
    private final TtlResultCache statsResultCache; // summary/equity-curve 결과 캐시

    TradingStatsService(AccountPort accountPort, StrategyPort strategyPort, StrategyCyclePort strategyCyclePort,
                        CyclePositionPort cyclePositionPort, CycleCapitalEventPort cycleCapitalEventPort, Clock clock) {
        this.accountPort = accountPort;
        this.strategyPort = strategyPort;
        this.strategyCyclePort = strategyCyclePort;
        this.cyclePositionPort = cyclePositionPort;
        this.cycleCapitalEventPort = cycleCapitalEventPort;
        this.clock = clock;
        this.statsResultCache = new TtlResultCache(clock);
    }

    // 사이클 신구 비교 — createdAt, 동률은 UUIDv7 id(시간순)로 깬다
    private static final Comparator<StrategyCycle> NEWER_CYCLE =
            Comparator.comparing(StrategyCycle::createdAt).thenComparing(StrategyCycle::id);

    // getSummary 캐시 키
    private record SummaryKey(UUID userId, Currency currency) {}

    // getEquityCurve 캐시 키 — 파라미터 조합별로 분리
    private record EquityCurveKey(UUID userId, Currency currency, StrategyType type, LocalDate from, LocalDate to) {}

    // 사이클 + 소속 전략 조인 뷰 — 원금 = 개장 시가 평가 + 추가·인출 합계(매매 계약 startAmount는 쓰지 않음), 자산 = 예수금 + 보유 평가액(시드 점유 전략 공통)
    private record CycleView(StrategyCycle cycle, Strategy strategy, List<CycleCapitalEvent> events, Currency currency) {
        boolean closed() {
            return cycle.endAmount() != null && cycle.isEnded();
        }

        BigDecimal principal() {
            return cycle.openAmount().add(CapitalLedgerTimeline.principalDeltaTotal(events));
        }

        // date(KST) 시점의 원금 — 그 뒤 추가·인출분을 현재 원금에서 되돌린다
        BigDecimal principalOn(LocalDate date) {
            return principal().subtract(CapitalLedgerTimeline.principalDeltaAfter(events, date));
        }

        // 구간 손익(종료 − 시작) — 승률·평균 수익률·성과 목록·체인 보정 기준
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
        invalidateUserCache(event.userId());
    }

    // 투자금 추가·인출·시드 수정·VR 재설정·체결 보정·재개·삭제 — 트랜잭션 동기화가 활성이면 커밋 뒤(afterCommit)에, 아니면 즉시 그 사용자 캐시를 비운다.
    // 삭제(StrategyService.delete)는 NOT_SUPPORTED지만 동기화는 활성(SYNCHRONIZE_ALWAYS)이라 무효화가 메서드 스코프 종료 시점에 실행된다.
    // @TransactionalEventListener는 모듈리스 event_publication 행을 남겨 메모리 캐시 무효화엔 과하므로 @EventListener + 동기화 콜백을 쓴다
    @EventListener
    void onStrategyCapitalChanged(StrategyCapitalChangedEvent event) {
        UUID userId = event.userId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidateUserCache(userId);
                }
            });
        } else {
            invalidateUserCache(userId);
        }
    }

    private void invalidateUserCache(UUID userId) {
        statsResultCache.invalidateIf(key -> key instanceof SummaryKey s && s.userId().equals(userId)
                || key instanceof EquityCurveKey k && k.userId().equals(userId));
    }

    @Override
    public StatsSummary getSummary(UUID userId, Currency currency) {
        return statsResultCache.getOrCompute(
                new SummaryKey(userId, currency), CURVE_CACHE_TTL, () -> computeSummary(userId, currency));
    }

    private StatsSummary computeSummary(UUID userId, Currency currency) {
        List<CycleView> all = loadCycles(userId, a -> a.broker() != Broker.MOCK);
        // 사용 통화 목록 — 요청 통화와 무관하게 MOCK 제외 전체 기준(enum 선언 순서)
        List<Currency> availableCurrencies = all.stream().map(CycleView::currency).distinct().sorted().toList();
        List<CycleView> cycles = all.stream().filter(v -> v.currency() == currency).toList();
        Map<UUID, BigDecimal> unrealizedByCycle = unrealizedByCycle(cycles);

        Map<StrategyType, List<CycleView>> byType = cycles.stream()
                .collect(Collectors.groupingBy(v -> v.strategy().type(),
                        () -> new EnumMap<>(StrategyType.class), Collectors.toList()));

        // 개장 시가 기준 실현손익 계산용 전체 포지션 행 — 종료·진행 중 사이클 모두(CQ-68), 사이클이 없으면 조회 생략
        Set<UUID> cycleIds = cycles.stream().map(v -> v.cycle().id()).collect(Collectors.toSet());
        Map<UUID, List<CyclePosition>> positionsByCycle = cycleIds.isEmpty() ? Map.of()
                : cyclePositionPort.findByCycleIdsAndRange(cycleIds, Instant.EPOCH, KstDays.endExclusive(LocalDate.now(clock))).stream()
                        .sorted(Comparator.comparing(CyclePosition::createdAt).thenComparing(CyclePosition::id)) // 동시각은 UUIDv7 id로 고정 — V17 백필의 첫 행 선택과 같은 순서
                        .collect(Collectors.groupingBy(CyclePosition::strategyCycleId));

        List<StrategyTypeStats> typeStats = byType.entrySet().stream()
                .map(e -> toTypeStats(e.getKey(), e.getValue(), unrealizedByCycle, positionsByCycle, currency))
                .toList();

        BigDecimal totalRealized = sum(typeStats.stream().map(StrategyTypeStats::realizedPnl), currency);
        BigDecimal totalUnrealized = sum(typeStats.stream().map(StrategyTypeStats::unrealizedPnl), currency);
        BigDecimal activePrincipal = sum(cycles.stream()
                .filter(v -> !v.closed()).map(CycleView::principal), currency);

        return new StatsSummary(totalRealized, totalUnrealized, activePrincipal, typeStats, availableCurrencies);
    }

    @Override
    public EquityCurve getEquityCurve(UUID userId, Currency currency, StrategyType type, LocalDate from, LocalDate to) {
        return statsResultCache.getOrCompute(
                new EquityCurveKey(userId, currency, type, from, to), CURVE_CACHE_TTL,
                () -> computeEquityCurve(userId, currency, type, from, to));
    }

    private EquityCurve computeEquityCurve(UUID userId, Currency currency, StrategyType type, LocalDate from, LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(clock);
        // PAUSED 전략처럼 스냅샷 갱신이 멈춘 사이클의 carry-forward 상태를 보장하기 위해
        // 전체 범위 조회 (사용자당 스냅샷 수천 건 규모라 허용)
        Instant fromInstant = Instant.EPOCH;
        Instant toInstant = KstDays.endExclusive(effectiveTo); // KST 자정 경계 — 04:30 배치 스냅샷이 해당 KST 일자에 속함

        List<CycleView> cycles = loadCycles(userId, a -> a.broker() != Broker.MOCK).stream()
                .filter(v -> v.currency() == currency)
                .filter(v -> type == null || v.strategy().type() == type)
                .toList();
        Set<UUID> cycleIds = cycles.stream().map(v -> v.cycle().id()).collect(Collectors.toSet());
        // userId 스코프는 loadCycles가 이미 보장 — DB 조회 자체를 cycleIds로 좁혀 불필요한 타입 전체 조회 방지
        List<CyclePosition> raw = cyclePositionPort.findByCycleIdsAndRange(cycleIds, fromInstant, toInstant);
        // 투자금 추가·인출로 덮어쓴 과거 스냅샷을 이력으로 복원
        List<CyclePosition> positions = CapitalLedgerTimeline.restore(raw,
                cycles.stream().flatMap(v -> v.events().stream()).toList());
        List<EquityPoint> points = buildPoints(cycles, positions, from, effectiveTo, currency);
        return new EquityCurve(points);
    }

    @Override
    public CyclePerformancePage getCyclePerformances(UUID userId, StrategyType type, UUID accountId,
                                                     StrategyTicker ticker, CycleStatusFilter status, int page, int size) {
        // loadCycles가 본인 계좌로 한정하므로 타 사용자 accountId는 자연히 빈 결과
        List<CycleView> filtered = loadCycles(userId, a -> true).stream()
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

    // accountFilter: 합산 조회(누적자산추이·전략유형비교)는 모의계좌(MOCK)를 뺀 계좌만, 사이클 성과 목록은 이력 확인이 목적이라 전부(a -> true).
    // 통화(broker.getCurrency())는 사이클마다 달고 가며, 합산 경로는 호출 측에서 요청 통화로 거른다(통화 간 환산 없음).
    private List<CycleView> loadCycles(UUID userId, Predicate<Account> accountFilter) {
        List<Account> accounts = accountPort.findByUserId(userId).stream().filter(accountFilter).toList();
        Map<UUID, Currency> currencyByAccount = accounts.stream()
                .collect(Collectors.toMap(Account::id, a -> a.broker().getCurrency()));
        List<UUID> accountIds = accounts.stream().map(Account::id).toList();
        if (accountIds.isEmpty()) return List.of();
        Map<UUID, Strategy> strategies = strategyPort.findByAccountIds(accountIds).values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(Strategy::id, Function.identity()));
        if (strategies.isEmpty()) return List.of();
        List<StrategyCycle> cycles = strategyCyclePort.findByStrategyIds(strategies.keySet());
        Map<UUID, List<CycleCapitalEvent>> eventsByCycle = cycleCapitalEventPort
                .findByCycleIds(cycles.stream().map(StrategyCycle::id).toList()).stream()
                .collect(Collectors.groupingBy(CycleCapitalEvent::strategyCycleId));
        return cycles.stream()
                .map(c -> {
                    Strategy strategy = strategies.get(c.strategyId());
                    return new CycleView(c, strategy, eventsByCycle.getOrDefault(c.id(), List.of()),
                            currencyByAccount.get(strategy.accountId()));
                })
                .toList();
    }

    // 진행 중 사이클의 미실현 = 최신 스냅샷 자산 - 원금 (스냅샷 없으면 제외)
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
                                          Map<UUID, BigDecimal> unrealizedByCycle,
                                          Map<UUID, List<CyclePosition>> positionsByCycle, Currency currency) {
        List<CycleView> closed = views.stream().filter(CycleView::closed).toList();
        List<CycleView> active = views.stream().filter(v -> !v.closed()).toList();

        // 총 손익 = 종료 구간 손익 + 진행 중 평가손익 — 실현(매도 확정분, 진행 중 사이클 포함)만 장부가로 갈라내고 나머지는 미실현으로 둬 합을 보존한다
        // 응답 금액은 통화 자릿수로 반올림한다 — 총손익을 원값 합으로 한 번 반올림하고 미실현 = 총손익 − 실현이라 한 응답 안의 합이 맞는다
        BigDecimal totalPnl = sum(Stream.concat(closed.stream().map(CycleView::realizedPnl),
                active.stream().map(v -> unrealizedByCycle.getOrDefault(v.cycle().id(), BigDecimal.ZERO))), currency);
        BigDecimal realizedPnl = sum(views.stream().map(v -> settledPnl(v, positionsByCycle.getOrDefault(v.cycle().id(), List.of()))), currency);
        BigDecimal unrealizedPnl = totalPnl.subtract(realizedPnl);

        BigDecimal winRate = null;
        BigDecimal avgReturnRate = null;
        BigDecimal avgDurationDays = null;
        if (!closed.isEmpty()) {
            // 승률 = 수익 / (수익 + 손실) — 본전(손익 0) 사이클은 분자·분모에서 뺀다. 전부 본전이면 null
            long wins = closed.stream().filter(v -> v.realizedPnl().signum() > 0).count();
            long losses = closed.stream().filter(v -> v.realizedPnl().signum() < 0).count();
            if (wins + losses > 0) {
                winRate = BigDecimal.valueOf(wins)
                        .divide(BigDecimal.valueOf(wins + losses), 4, RoundingMode.HALF_UP);
            }
            // 원금이 0 이하인 사이클(VR 적립식 등)은 수익률 계산에서 제외한다.
            List<CycleView> returnable = closed.stream()
                    .filter(v -> v.principal().signum() > 0)
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

    // 사이클의 실현손익(매도 확정분) — 종료 사이클이 보유 0으로 끝났으면 종료 − 원금,
    // 보유가 남은 종료 사이클(VR 롤오버·재설정)과 진행 중 사이클(쿼터매도·PRIVACY 매도·DCA SELL)은 개장 시가 기준 장부가 실현분. 진행 중인데 스냅샷이 없으면 0
    private static BigDecimal settledPnl(CycleView v, List<CyclePosition> rows) {
        if (v.closed() && (rows.isEmpty() || rows.getLast().holdings().signum() == 0)) return v.realizedPnl();
        if (rows.isEmpty()) return BigDecimal.ZERO;
        return CapitalLedgerTimeline.openBasisRealized(v.cycle().openAmount(), rows, v.events());
    }

    // 날짜(KST)별 사이클 최신 스냅샷 carry-forward 합산.
    // 사이클 종료일 이후, 또는 같은 전략의 후속 사이클 스냅샷이 생긴 날부터는 해당 사이클을 자산·원금에서 제외한다.
    // 후속 없이 끝난 전략은 자산에서 빠져도 체인 전체 손익을 원금에서 계속 빼 둔다(CQ-70).
    // 롤오버·회전으로 후속 사이클이 있는 종료 사이클은 후속 스냅샷이 나타날 때까지 마지막 값을 이어 쓴다(지연 롤오버 공백 방지).
    // 단 교체된 앞선 사이클의 손익이 사라지지 않도록 체인 보정(ChainAdjustment)을 최신 사이클에 더한다.
    // 체인은 latest에 들어온 같은 전략 사이클(positions는 전체 범위 조회라 과거 스냅샷이 모두 있다)이고, 사이클 메타는 cycleById(전체 사이클)에서 읽는다.
    private List<EquityPoint> buildPoints(List<CycleView> cycles, List<CyclePosition> positions,
                                          LocalDate from, LocalDate to, Currency currency) {
        Map<UUID, CycleView> cycleById = cycles.stream()
                .collect(Collectors.toMap(v -> v.cycle().id(), Function.identity()));

        // 후속 사이클이 있는 사이클 id — 전략별로 가장 나중 사이클을 뺀 나머지
        Set<UUID> hasSuccessor = new HashSet<>();
        cycles.stream().collect(Collectors.groupingBy(v -> v.cycle().strategyId())).values().forEach(group -> {
            List<StrategyCycle> sorted = group.stream().map(CycleView::cycle).sorted(NEWER_CYCLE).toList();
            sorted.subList(0, sorted.size() - 1).forEach(c -> hasSuccessor.add(c.id()));
        });

        // positions는 created_at 오름차순 — 날짜별로 사이클당 마지막 스냅샷이 남는다
        TreeMap<LocalDate, Map<UUID, CyclePosition>> byDate = new TreeMap<>();
        for (CyclePosition pos : positions) {
            LocalDate date = pos.createdAt().atZone(TimeZones.KST).toLocalDate();
            byDate.computeIfAbsent(date, _ -> new HashMap<>()).put(pos.strategyCycleId(), pos);
        }

        Map<UUID, CyclePosition> latest = new HashMap<>(); // carry-forward 상태
        Map<UUID, TreeSet<StrategyCycle>> chains = new HashMap<>(); // 전략별 체인 (latest에 들어온 사이클을 생성 순서로 정렬)
        Map<UUID, ChainAdjustment> adjustments = new HashMap<>(); // 전략별 체인 보정 합계 — 체인이 늘 때만 재계산
        List<EquityPoint> points = new ArrayList<>();
        for (var entry : byDate.entrySet()) {
            LocalDate date = entry.getKey();
            latest.putAll(entry.getValue());
            // 전략별로 이 시점 스냅샷이 있는 가장 나중 사이클(createdAt, 동률은 UUIDv7 id) — 교체(청산 회전·VR 롤오버·재설정)일의 이중 합산 방지용. 새로 들어온 사이클만 증분 반영
            for (UUID cycleId : entry.getValue().keySet()) {
                CycleView view = cycleById.get(cycleId);
                if (view == null) continue;
                UUID strategyId = view.cycle().strategyId();
                TreeSet<StrategyCycle> chain = chains.computeIfAbsent(strategyId, _ -> new TreeSet<>(NEWER_CYCLE));
                if (chain.add(view.cycle()) && chain.size() > 1) adjustments.put(strategyId, chainAdjustment(chain, cycleById));
            }
            if (from != null && date.isBefore(from)) continue;
            if (date.isAfter(to)) break;

            BigDecimal asset = BigDecimal.ZERO;
            BigDecimal principal = BigDecimal.ZERO;
            for (var posEntry : latest.entrySet()) {
                CycleView view = cycleById.get(posEntry.getKey());
                if (view == null) continue;
                LocalDate endDate = view.cycle().endDate();
                ChainAdjustment adj = adjustments.get(view.cycle().strategyId());
                if (endDate != null && date.isAfter(endDate) && !hasSuccessor.contains(view.cycle().id())) {
                    // 후속 없이 끝난 전략(시드 정책 NONE 등) — 자산에서는 빠지고 체인 전체 손익만 원금에서 계속 뺀다(곡선 손익 = summary 총손익, CQ-70)
                    if (view.closed()) {
                        principal = principal.subtract(view.realizedPnl());
                        if (adj != null) principal = principal.subtract(adj.realized());
                    }
                    continue;
                }
                if (!view.cycle().id().equals(chains.get(view.cycle().strategyId()).last().id())) continue; // 후속 사이클이 있으면 탈락
                asset = asset.add(posEntry.getValue().totalAssetWithAvgPriceFallback());
                principal = principal.add(view.principalOn(date));
                if (adj != null) { // 앞선 종료 사이클의 손익은 유지하고 전략 밖으로 빠진 금액(surplus)만 자산·원금에 더한다
                    asset = asset.add(adj.surplus());
                    principal = principal.subtract(adj.realized()).add(adj.surplus());
                }
            }
            points.add(new EquityPoint(date,
                    currency.round(asset), currency.round(principal)));
        }
        return points;
    }

    // 체인 보정 합계 — 마지막 사이클 앞의 종료 사이클 i마다 realized_i = end-start, surplus_i = max(0, end_i - open_{i+1})
    private record ChainAdjustment(BigDecimal realized, BigDecimal surplus) {}

    private static ChainAdjustment chainAdjustment(TreeSet<StrategyCycle> chain, Map<UUID, CycleView> cycleById) {
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal surplus = BigDecimal.ZERO;
        StrategyCycle[] cs = chain.toArray(StrategyCycle[]::new);
        for (int i = 0; i < cs.length - 1; i++) {
            CycleView prev = cycleById.get(cs[i].id());
            if (!prev.closed()) continue;
            realized = realized.add(prev.realizedPnl());
            surplus = surplus.add(prev.cycle().endAmount().subtract(cycleById.get(cs[i + 1].id()).cycle().openAmount()).max(BigDecimal.ZERO));
        }
        return new ChainAdjustment(realized, surplus);
    }

    private CyclePerformance toPerformance(CycleView v, Map<UUID, CyclePosition> latestPositions) {
        StrategyCycle c = v.cycle();
        // 종료 금액(종료 사이클) 또는 최신 스냅샷 평가액(진행 중)
        BigDecimal endAmount = v.closed() ? c.endAmount()
                : Optional.ofNullable(latestPositions.get(c.id())).map(CyclePosition::totalAssetWithAvgPriceFallback).orElse(null);
        BigDecimal rawPrincipal = v.principal();
        BigDecimal rawPnl = endAmount != null ? endAmount.subtract(rawPrincipal) : null;
        // 원금이 0 이하인 사이클(VR 적립식 등)은 수익률이 정의되지 않는다. 수익률은 반올림 전 값으로 계산한다.
        BigDecimal returnRate = (rawPnl != null && rawPrincipal.signum() > 0)
                ? rawPnl.divide(rawPrincipal, 4, RoundingMode.HALF_UP) : null;
        // 응답 금액은 사이클 통화 자릿수로 반올림하고 손익은 반올림된 값끼리 뺀다
        Currency currency = v.currency();
        BigDecimal principal = currency.round(rawPrincipal);
        endAmount = endAmount != null ? currency.round(endAmount) : null;
        BigDecimal pnl = endAmount != null ? endAmount.subtract(principal) : null;
        LocalDate durationEnd = v.closed() ? c.endDate() : LocalDate.now(clock);
        // 시작예정일이 미래인 사이클은 아직 경과일이 없다 — 음수 대신 0
        int durationDays = (int) Math.max(0, ChronoUnit.DAYS.between(c.startDate(), durationEnd));
        return new CyclePerformance(c.id(), v.strategy().accountId(), v.strategy().type(), v.strategy().ticker(),
                c.startDate(), c.endDate(), principal, endAmount, pnl, returnRate,
                durationDays, v.closed());
    }

    // 금액 합계를 통화 자릿수로 반올림
    private static BigDecimal sum(Stream<BigDecimal> stream, Currency currency) {
        return currency.round(stream.reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
