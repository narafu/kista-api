package com.kista.trading.stats.application.service;

import com.kista.sharedkernel.TimeZones;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.trading.stats.domain.model.*;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.CyclePositionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

// StatsService(com.kista.stats)에서 이관된 trading 소유 통계(summary/equity-curve/cycles) 테스트 —
// housing/ETF 벤치마크 비교 테스트는 api의 StatsServiceTest에 잔류
@ExtendWith(MockitoExtension.class)
class TradingStatsServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    // getOrCompute가 null을 반환하지 않도록 mock이 아닌 실제 캐시 인스턴스 사용 (@InjectMocks가 생성자로 주입)
    @Spy TradingStatsResultCache statsResultCache = new TradingStatsResultCache();
    @InjectMocks TradingStatsService statsService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final UUID PRIVACY_STRATEGY_ID = UUID.randomUUID();

    private static final Strategy STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, StrategyType.INFINITE, StrategyStatus.ACTIVE,
            StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    private static final Strategy PRIVACY_STRATEGY = new Strategy(
            PRIVACY_STRATEGY_ID, ACCOUNT_ID, StrategyType.PRIVACY, StrategyStatus.ACTIVE,
            StrategyTicker.SOXL, StrategyCycleSeedType.NONE);

    // Account는 record(final) — mock(Account.class) 대신 실제 인스턴스 생성 (AccountServiceTest 패턴)
    private static Account testAccount() {
        return new Account(ACCOUNT_ID, USER_ID, "테스트계좌",
                "74420614", "appKey", "appSecret", null,
                Broker.KIS, null);
    }

    private void stubUserWithStrategy() {
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY)));
    }

    private static StrategyCycle closedCycle(String start, String end, String startDate, String endDate) {
        return new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, null,
                new BigDecimal(start), new BigDecimal(end),
                LocalDate.parse(startDate), LocalDate.parse(endDate),
                Instant.parse(startDate + "T00:00:00Z"), null);
    }

    private static StrategyCycle activeCycle(String start, String startDate) {
        return new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, null,
                new BigDecimal(start), null, LocalDate.parse(startDate), null,
                Instant.parse(startDate + "T00:00:00Z"), null);
    }

    private static StrategyCycle activeCycle(UUID strategyId, String start, String startDate) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, null,
                new BigDecimal(start), null, LocalDate.parse(startDate), null,
                Instant.parse(startDate + "T00:00:00Z"), null);
    }

    // holdings=0 스냅샷 — 자산 = usdDeposit
    private static CyclePosition depositSnapshot(UUID cycleId, String deposit, String createdAt) {
        return new CyclePosition(UUID.randomUUID(), cycleId, new BigDecimal(deposit),
                null, null, 0, Instant.parse(createdAt), null);
    }

    @Test
    void 종료_사이클_실현손익과_승률을_집계한다() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31"), // +100, 30일
                closedCycle("1000.00", "950.00", "2026-02-01", "2026-02-11")));  // -50, 10일

        StatsSummary summary = statsService.getSummary(USER_ID);

        assertThat(summary.totalRealizedPnl()).isEqualByComparingTo("50.00");
        StrategyTypeStats infinite = summary.byType().get(0);
        assertThat(infinite.type()).isEqualTo(StrategyType.INFINITE);
        assertThat(infinite.closedCycleCount()).isEqualTo(2);
        assertThat(infinite.winRate()).isEqualByComparingTo("0.5");
        assertThat(infinite.avgDurationDays()).isEqualByComparingTo("20.0");
    }

    @Test
    void 진행_중_사이클은_최신_스냅샷으로_미실현손익을_계산한다() {
        stubUserWithStrategy();
        StrategyCycle active = new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, null,
                new BigDecimal("1000.00"), null, LocalDate.parse("2026-06-01"), null,
                Instant.parse("2026-06-01T00:00:00Z"), null);
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));
        // 자산 = 500 + 10 × 55.00 = 1050 → 미실현 +50
        when(cyclePositionPort.findLatestByCycleIds(Set.of(active.id()))).thenReturn(Map.of(active.id(),
                new CyclePosition(UUID.randomUUID(), active.id(), new BigDecimal("500.00"),
                        new BigDecimal("55.00"), new BigDecimal("50.00"), 10, Instant.now(), null)));

        StatsSummary summary = statsService.getSummary(USER_ID);

        assertThat(summary.totalUnrealizedPnl()).isEqualByComparingTo("50.00");
        assertThat(summary.activePrincipal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void 레거시_VR_진행_사이클은_개장_포지션_총자산으로_원금과_미실현손익을_복원한다() {
        UUID vrStrategyId = UUID.randomUUID();
        Strategy vrStrategy = new Strategy(
                vrStrategyId, ACCOUNT_ID, StrategyType.VR, StrategyStatus.ACTIVE,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(
                UUID.randomUUID(), vrStrategyId, null,
                new BigDecimal("1000.00"), null,
                LocalDate.of(2026, 6, 1), null, Instant.parse("2026-06-01T00:00:00Z"), null);
        CyclePosition opening = new CyclePosition(
                UUID.randomUUID(), cycle.id(), new BigDecimal("1000.00"),
                new BigDecimal("120.00"), new BigDecimal("100.00"), 5, Instant.now(), null);
        CyclePosition latest = new CyclePosition(
                UUID.randomUUID(), cycle.id(), new BigDecimal("800.00"),
                new BigDecimal("180.00"), new BigDecimal("100.00"), 5, Instant.now(), null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(vrStrategy)));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findFirstByCycleIds(Set.of(cycle.id()))).thenReturn(Map.of(cycle.id(), opening));
        when(cyclePositionPort.findLatestByCycleIds(Set.of(cycle.id()))).thenReturn(Map.of(cycle.id(), latest));

        StatsSummary summary = statsService.getSummary(USER_ID);

        assertThat(summary.activePrincipal()).isEqualByComparingTo("1600.00");
        assertThat(summary.totalUnrealizedPnl()).isEqualByComparingTo("100.00");
        assertThat(summary.byType()).singleElement().satisfies(stats -> {
            assertThat(stats.type()).isEqualTo(StrategyType.VR);
            assertThat(stats.unrealizedPnl()).isEqualByComparingTo("100.00");
        });
    }

    @Test
    void 레거시_VR_개장_보유분에_종가가_없으면_저장된_startAmount를_유지한다() {
        UUID vrStrategyId = UUID.randomUUID();
        Strategy vrStrategy = new Strategy(
                vrStrategyId, ACCOUNT_ID, StrategyType.VR, StrategyStatus.ACTIVE,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(
                UUID.randomUUID(), vrStrategyId, null,
                new BigDecimal("1500.00"), null,
                LocalDate.of(2026, 6, 1), null, Instant.parse("2026-06-01T00:00:00Z"), null);
        CyclePosition openingWithoutClosingPrice = new CyclePosition(
                UUID.randomUUID(), cycle.id(), new BigDecimal("1000.00"),
                null, new BigDecimal("80.00"), 5, Instant.now(), null);
        CyclePosition latest = new CyclePosition(
                UUID.randomUUID(), cycle.id(), new BigDecimal("900.00"),
                new BigDecimal("140.00"), new BigDecimal("80.00"), 5, Instant.now(), null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(vrStrategy)));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findFirstByCycleIds(Set.of(cycle.id())))
                .thenReturn(Map.of(cycle.id(), openingWithoutClosingPrice));
        when(cyclePositionPort.findLatestByCycleIds(Set.of(cycle.id()))).thenReturn(Map.of(cycle.id(), latest));

        StatsSummary summary = statsService.getSummary(USER_ID);

        assertThat(summary.activePrincipal()).isEqualByComparingTo("1500.00");
        assertThat(summary.totalUnrealizedPnl()).isEqualByComparingTo("100.00");
    }

    @Test
    void 레거시_VR_종료_사이클은_개장_포지션_총자산으로_실현손익과_성과를_복원한다() {
        UUID vrStrategyId = UUID.randomUUID();
        Strategy vrStrategy = new Strategy(
                vrStrategyId, ACCOUNT_ID, StrategyType.VR, StrategyStatus.ACTIVE,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(
                UUID.randomUUID(), vrStrategyId, null,
                new BigDecimal("1000.00"), new BigDecimal("1800.00"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                Instant.parse("2026-01-01T00:00:00Z"), null);
        CyclePosition opening = new CyclePosition(
                UUID.randomUUID(), cycle.id(), new BigDecimal("1000.00"),
                new BigDecimal("120.00"), new BigDecimal("100.00"), 5, Instant.now(), null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(vrStrategy)));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findFirstByCycleIds(Set.of(cycle.id()))).thenReturn(Map.of(cycle.id(), opening));

        StatsSummary summary = statsService.getSummary(USER_ID);
        CyclePerformance performance = statsService
                .getCyclePerformances(USER_ID, StrategyType.VR, null, 10)
                .items().getFirst();

        assertThat(summary.totalRealizedPnl()).isEqualByComparingTo("200.00");
        assertThat(summary.byType()).singleElement().satisfies(stats ->
                assertThat(stats.avgReturnRate()).isEqualByComparingTo("0.1250"));
        assertThat(performance.startAmount()).isEqualByComparingTo("1600.00");
        assertThat(performance.endAmount()).isEqualByComparingTo("1800.00");
        assertThat(performance.pnl()).isEqualByComparingTo("200.00");
        assertThat(performance.returnRate()).isEqualByComparingTo("0.1250");
    }

    @Test
    void equity_curve는_같은_날_같은_사이클의_최신_스냅샷만_합산한다() {
        stubUserWithStrategy();
        StrategyCycle active = new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, null,
                new BigDecimal("1000.00"), null, LocalDate.parse("2026-06-01"), null,
                Instant.parse("2026-06-01T00:00:00Z"), null);
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));
        // KST 2026-06-02 (UTC 06-01 20:00 / 06-01 20:30) 스냅샷 2건 — 최신 건만 반영
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                new CyclePosition(UUID.randomUUID(), active.id(), new BigDecimal("900.00"),
                        new BigDecimal("10.00"), null, 5, Instant.parse("2026-06-01T20:00:00Z"), null),
                new CyclePosition(UUID.randomUUID(), active.id(), new BigDecimal("800.00"),
                        new BigDecimal("10.00"), null, 20, Instant.parse("2026-06-01T20:30:00Z"), null)));
        EquityCurve curve = statsService.getEquityCurve(
                USER_ID, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(1);
        assertThat(curve.points().get(0).date()).isEqualTo(LocalDate.parse("2026-06-02"));
        // 800 + 20 × 10.00 = 1000
        assertThat(curve.points().get(0).totalAsset()).isEqualByComparingTo("1000.00");
        assertThat(curve.points().get(0).principal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void equity_curve는_전략_type으로_사이클을_필터링한다() {
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID)))
                .thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY, PRIVACY_STRATEGY)));
        StrategyCycle infinite = activeCycle(STRATEGY_ID, "1000.00", "2026-06-01");
        StrategyCycle privacy = activeCycle(PRIVACY_STRATEGY_ID, "2000.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(infinite, privacy));
        // type=PRIVACY 필터 시 DB 조회 자체가 privacy 사이클 ID로 좁혀지므로 infinite 스냅샷은 stub하지 않는다
        when(cyclePositionPort.findByCycleIdsAndRange(eq(Set.of(privacy.id())), any(), any())).thenReturn(List.of(
                depositSnapshot(privacy.id(), "2300.00", "2026-06-02T01:00:00Z")));

        EquityCurve curve = statsService.getEquityCurve(
                USER_ID, StrategyType.PRIVACY,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(1);
        assertThat(curve.points().getFirst().date()).isEqualTo(LocalDate.parse("2026-06-02"));
        assertThat(curve.points().getFirst().totalAsset()).isEqualByComparingTo("2300.00");
        assertThat(curve.points().getFirst().principal()).isEqualByComparingTo("2000.00");
    }

    @Test
    void 사이클_성과_목록은_커서로_페이지네이션한다() {
        stubUserWithStrategy();
        StrategyCycle c1 = closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31");
        StrategyCycle c2 = closedCycle("1000.00", "1200.00", "2026-02-01", "2026-02-28");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c1, c2));

        CyclePerformancePage page = statsService.getCyclePerformances(USER_ID, null, null, 1);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).startDate()).isEqualTo(LocalDate.parse("2026-02-01")); // 최신순
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(c2.createdAt());
    }

    @Test
    void startAmount가_0인_종료_사이클은_수익률을_null로_처리한다() {
        stubUserWithStrategy();
        // VR 적립식: startAmount=0 사이클이 정상 존재 (등록·롤오버 종료 모두 가능)
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("0.00", "500.00", "2026-01-01", "2026-01-31")));

        StatsSummary summary = statsService.getSummary(USER_ID);

        StrategyTypeStats stats = summary.byType().get(0);
        assertThat(stats.closedCycleCount()).isEqualTo(1);
        assertThat(stats.winRate()).isEqualByComparingTo("1"); // 승률·실현손익은 전체 closed 기준 유지
        assertThat(stats.avgReturnRate()).isNull(); // 0-start만 있으면 평균 수익률 없음
        assertThat(stats.realizedPnl()).isEqualByComparingTo("500.00");

        CyclePerformancePage page = statsService.getCyclePerformances(USER_ID, null, null, 10);

        assertThat(page.items().get(0).pnl()).isEqualByComparingTo("500.00");
        assertThat(page.items().get(0).returnRate()).isNull();
    }

    @Test
    void 사이클_성과_목록은_커서_이후_항목만_반환한다() {
        stubUserWithStrategy();
        StrategyCycle c1 = closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31");
        StrategyCycle c2 = closedCycle("1000.00", "1200.00", "2026-02-01", "2026-02-28");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c1, c2));

        // 1페이지 마지막 커서(c2.createdAt) 이후 → createdAt < cursor인 c1만
        CyclePerformancePage page = statsService.getCyclePerformances(USER_ID, null, c2.createdAt(), 10);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).cycleId()).isEqualTo(c1.id());
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void equityCurve_조회_경계는_KST_자정() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of());
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);

        statsService.getEquityCurve(USER_ID, null, null, LocalDate.of(2026, 7, 18));

        // to=2026-07-18 → toInstant = 2026-07-19T00:00 KST = 2026-07-18T15:00:00Z
        verify(cyclePositionPort).findByCycleIdsAndRange(any(), any(), toCaptor.capture());
        assertThat(toCaptor.getValue())
                .isEqualTo(LocalDate.of(2026, 7, 19).atStartOfDay(TimeZones.KST).toInstant());
    }

    @Test
    void equity_curve는_스냅샷이_없는_날_직전_스냅샷을_carry_forward한다() {
        stubUserWithStrategy();
        StrategyCycle a = activeCycle("1000.00", "2026-06-01");
        StrategyCycle b = activeCycle("2000.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(a, b));
        // A는 KST 06-01·06-02 스냅샷, B는 06-01만 → 06-02 포인트에 B의 06-01 값이 carry-forward
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(a.id(), "1000.00", "2026-06-01T01:00:00Z"),
                depositSnapshot(b.id(), "2000.00", "2026-06-01T02:00:00Z"),
                depositSnapshot(a.id(), "1100.00", "2026-06-02T01:00:00Z")));
        EquityCurve curve = statsService.getEquityCurve(
                USER_ID, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(2);
        assertThat(curve.points().get(1).date()).isEqualTo(LocalDate.parse("2026-06-02"));
        // A 06-02(1100) + B 06-01 carry-forward(2000)
        assertThat(curve.points().get(1).totalAsset()).isEqualByComparingTo("3100.00");
        assertThat(curve.points().get(1).principal()).isEqualByComparingTo("3000.00");
    }

    @Test
    void equity_curve는_종료일이_지난_사이클을_자산과_원금에서_제외한다() {
        stubUserWithStrategy();
        StrategyCycle ended = closedCycle("1000.00", "1200.00", "2026-05-01", "2026-06-01");
        StrategyCycle active = activeCycle("500.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(ended, active));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(ended.id(), "1200.00", "2026-06-01T01:00:00Z"),
                depositSnapshot(active.id(), "500.00", "2026-06-01T02:00:00Z"),
                depositSnapshot(active.id(), "550.00", "2026-06-02T01:00:00Z")));
        EquityCurve curve = statsService.getEquityCurve(
                USER_ID, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(2);
        // 06-01: 종료 사이클 포함 (endDate 당일까지 유효)
        assertThat(curve.points().get(0).totalAsset()).isEqualByComparingTo("1700.00");
        assertThat(curve.points().get(0).principal()).isEqualByComparingTo("1500.00");
        // 06-02: endDate(06-01) 경과 → 종료 사이클 제외
        assertThat(curve.points().get(1).totalAsset()).isEqualByComparingTo("550.00");
        assertThat(curve.points().get(1).principal()).isEqualByComparingTo("500.00");
    }

    @Test
    void 요약_전략유형비교는_모의계좌_사이클을_제외한다() {
        UUID mockAccountId = UUID.randomUUID();
        Account mockAccount = new Account(mockAccountId, USER_ID, "모의계좌",
                "00000000", "key", "secret", null, Broker.MOCK, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(), mockAccount));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY)));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31")));

        StatsSummary summary = statsService.getSummary(USER_ID);

        assertThat(summary.totalRealizedPnl()).isEqualByComparingTo("100.00");
        assertThat(summary.byType()).hasSize(1);
        verify(strategyPort, never()).findByAccountIds(argThat(ids -> ids.contains(mockAccountId)));
    }

    @Test
    void 누적자산추이는_모의계좌_사이클을_제외한다() {
        UUID mockAccountId = UUID.randomUUID();
        Account mockAccount = new Account(mockAccountId, USER_ID, "모의계좌",
                "00000000", "key", "secret", null, Broker.MOCK, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(), mockAccount));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY)));
        StrategyCycle active = activeCycle("1000.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(active.id(), "1000.00", "2026-06-02T01:00:00Z")));

        EquityCurve curve = statsService.getEquityCurve(
                USER_ID, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(1);
        verify(strategyPort, never()).findByAccountIds(argThat(ids -> ids.contains(mockAccountId)));
    }

    @Test
    void 사이클_성과_목록은_모의계좌_사이클도_포함한다() {
        UUID mockAccountId = UUID.randomUUID();
        Account mockAccount = new Account(mockAccountId, USER_ID, "모의계좌",
                "00000000", "key", "secret", null, Broker.MOCK, null);
        UUID mockStrategyId = UUID.randomUUID();
        Strategy mockStrategy = new Strategy(mockStrategyId, mockAccountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle mockCycle = new StrategyCycle(UUID.randomUUID(), mockStrategyId, null,
                new BigDecimal("500.00"), new BigDecimal("600.00"),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"),
                Instant.parse("2026-01-01T00:00:00Z"), null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(), mockAccount));
        // 사이클 성과 목록(excludeMock=false)은 모의계좌 포함 전체 accountIds로 배치 조회한다
        when(strategyPort.findByAccountIds(argThat(ids ->
                ids.contains(ACCOUNT_ID) && ids.contains(mockAccountId))))
                .thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY), mockAccountId, List.of(mockStrategy)));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(mockCycle));

        CyclePerformancePage page = statsService.getCyclePerformances(USER_ID, null, null, 10);

        assertThat(page.items()).extracting(CyclePerformance::accountId).containsExactly(mockAccountId);
    }

    // ── 결과 캐시(TTL 5분) ───────────────────────────────────────────────────

    @Test
    void getSummary는_TTL_내_재조회시_캐시를_반환하고_DB를_다시_조회하지_않는다() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31")));

        StatsSummary first = statsService.getSummary(USER_ID);
        StatsSummary second = statsService.getSummary(USER_ID);

        assertThat(second).isEqualTo(first);
        verify(accountPort, times(1)).findByUserId(USER_ID);
        verify(strategyCyclePort, times(1)).findByStrategyIds(any());
    }

    @Test
    void getEquityCurve는_파라미터별로_캐시_키가_분리된다() {
        stubUserWithStrategy();
        StrategyCycle active = activeCycle("1000.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(active.id(), "1000.00", "2026-06-02T01:00:00Z")));

        // 같은 파라미터 2회 → 캐시 1회 조회
        statsService.getEquityCurve(USER_ID, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        statsService.getEquityCurve(USER_ID, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        // to가 다른 호출 → 별도 캐시 키로 재조회
        statsService.getEquityCurve(USER_ID, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-07-31"));

        verify(cyclePositionPort, times(2)).findByCycleIdsAndRange(any(), any(), any());
    }
}
