package com.kista.tradingcore.stats.application.service;

import com.kista.shared.support.FixedClockTestConfig;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.stats.domain.model.*;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.CycleCapitalEventPort;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import org.junit.jupiter.api.BeforeEach;
import com.kista.tradingcore.trading.application.event.TradingReportReadyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.kista.tradingcore.trading.application.event.StrategyCapitalChangedEvent;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;

// BenchmarkService(com.kista.api.benchmark)에서 이관된 trading 소유 통계(summary/equity-curve/cycles) 테스트 —
// housing/ETF 벤치마크 비교 테스트는 api의 BenchmarkServiceTest에 잔류
@ExtendWith(MockitoExtension.class)
class TradingStatsServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock CycleCapitalEventPort cycleCapitalEventPort;
    TradingStatsService statsService;

    @BeforeEach
    void setUp() {
        statsService = new TradingStatsService(accountPort, strategyPort, strategyCyclePort, cyclePositionPort, cycleCapitalEventPort, FixedClockTestConfig.FIXED);
    }

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
                Broker.KIS, null, null);
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

    // holdings=0 스냅샷 — 자산 = deposit
    private static CyclePosition depositSnapshot(UUID cycleId, String deposit, String createdAt) {
        return new CyclePosition(UUID.randomUUID(), cycleId, new BigDecimal(deposit),
                null, null, BigDecimal.valueOf(0), Instant.parse(createdAt), null);
    }

    @Test
    void 종료_사이클_실현손익과_승률을_집계한다() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31"), // +100, 30일
                closedCycle("1000.00", "950.00", "2026-02-01", "2026-02-11")));  // -50, 10일

        StatsSummary summary = statsService.getSummary(USER_ID, Currency.USD);

        assertThat(summary.totalRealizedPnl()).isEqualByComparingTo("50.00");
        StrategyTypeStats infinite = summary.byType().getFirst();
        assertThat(infinite.type()).isEqualTo(StrategyType.INFINITE);
        assertThat(infinite.closedCycleCount()).isEqualTo(2);
        assertThat(infinite.winRate()).isEqualByComparingTo("0.5");
        assertThat(infinite.avgDurationDays()).isEqualByComparingTo("20.0");
    }

    @Test
    void winRate_excludesBreakEvenCycles() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("1000.00", "1010.00", "2026-01-01", "2026-01-31"),
                closedCycle("1000.00", "990.00", "2026-02-01", "2026-02-11"),
                closedCycle("1000.00", "1000.00", "2026-03-01", "2026-03-11")));

        assertThat(statsService.getSummary(USER_ID, Currency.USD).byType().getFirst().winRate()).isEqualByComparingTo("0.5000");
    }

    @Test
    void winRate_nullWhenAllBreakEven() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("1000.00", "1000.00", "2026-01-01", "2026-01-31")));

        assertThat(statsService.getSummary(USER_ID, Currency.USD).byType().getFirst().winRate()).isNull();
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
                        new BigDecimal("55.00"), new BigDecimal("50.00"), BigDecimal.valueOf(10), Instant.now(), null)));

        StatsSummary summary = statsService.getSummary(USER_ID, Currency.USD);

        assertThat(summary.totalUnrealizedPnl()).isEqualByComparingTo("50.00");
        assertThat(summary.activePrincipal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void VR_사이클도_개장_평가를_원금으로_쓰고_진행_중_목록은_포지션을_조회하지_않는다() {
        UUID vrStrategyId = UUID.randomUUID();
        Strategy vrStrategy = new Strategy(
                vrStrategyId, ACCOUNT_ID, StrategyType.VR, StrategyStatus.ACTIVE,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        // 시작 금액(매매 계약)과 개장 평가를 다르게 둬 원금이 개장 평가에서 나오는지 가른다
        StrategyCycle cycle = cycleWithOpen(vrStrategyId, "1700.00", "1600.00", "1800.00");
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(vrStrategy)));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));

        CyclePerformance performance = statsService
                .getCyclePerformances(USER_ID, StrategyType.VR, null, null, null, 0, 10)
                .items().getFirst();

        assertThat(performance.startAmount()).isEqualByComparingTo("1600.00");
        assertThat(performance.pnl()).isEqualByComparingTo("200.00");
        assertThat(performance.returnRate()).isEqualByComparingTo("0.1250");
        verify(cyclePositionPort, never()).findByCycleIdsAndRange(any(), any(), any());
    }

    @Test
    void 시작예정일이_미래인_사이클의_경과일은_0이다() {
        stubUserWithStrategy();
        StrategyCycle scheduled = activeCycle(STRATEGY_ID, "1000.00",
                LocalDate.now(FixedClockTestConfig.FIXED).plusDays(3).toString());
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(scheduled));

        CyclePerformance performance = statsService
                .getCyclePerformances(USER_ID, null, null, null, null, 0, 10)
                .items().getFirst();

        assertThat(performance.durationDays()).isZero();
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
                        new BigDecimal("10.00"), null, BigDecimal.valueOf(5), Instant.parse("2026-06-01T20:00:00Z"), null),
                new CyclePosition(UUID.randomUUID(), active.id(), new BigDecimal("800.00"),
                        new BigDecimal("10.00"), null, BigDecimal.valueOf(20), Instant.parse("2026-06-01T20:30:00Z"), null)));
        EquityCurve curve = statsService.getEquityCurve(USER_ID, Currency.USD, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(1);
        assertThat(curve.points().getFirst().date()).isEqualTo(LocalDate.parse("2026-06-02"));
        // 800 + 20 × 10.00 = 1000
        assertThat(curve.points().getFirst().totalAsset()).isEqualByComparingTo("1000.00");
        assertThat(curve.points().getFirst().principal()).isEqualByComparingTo("1000.00");
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

        EquityCurve curve = statsService.getEquityCurve(USER_ID, Currency.USD, StrategyType.PRIVACY,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(1);
        assertThat(curve.points().getFirst().date()).isEqualTo(LocalDate.parse("2026-06-02"));
        assertThat(curve.points().getFirst().totalAsset()).isEqualByComparingTo("2300.00");
        assertThat(curve.points().getFirst().principal()).isEqualByComparingTo("2000.00");
    }

    @Test
    void 사이클_성과_목록은_번호로_페이지네이션한다() {
        stubUserWithStrategy();
        StrategyCycle c1 = closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31");
        StrategyCycle c2 = closedCycle("1000.00", "1200.00", "2026-02-01", "2026-02-28");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c1, c2));

        CyclePerformancePage page = statsService.getCyclePerformances(USER_ID, null, null, null, null, 0, 1);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().startDate()).isEqualTo(LocalDate.parse("2026-02-01")); // 최신순
        assertThat(page.page()).isZero();
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void startAmount가_0인_종료_사이클은_수익률을_null로_처리한다() {
        stubUserWithStrategy();
        // VR 적립식: startAmount=0 사이클이 정상 존재 (등록·롤오버 종료 모두 가능)
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("0.00", "500.00", "2026-01-01", "2026-01-31")));

        StatsSummary summary = statsService.getSummary(USER_ID, Currency.USD);

        StrategyTypeStats stats = summary.byType().getFirst();
        assertThat(stats.closedCycleCount()).isEqualTo(1);
        assertThat(stats.winRate()).isEqualByComparingTo("1"); // 승률·실현손익은 전체 closed 기준 유지
        assertThat(stats.avgReturnRate()).isNull(); // 0-start만 있으면 평균 수익률 없음
        assertThat(stats.realizedPnl()).isEqualByComparingTo("500.00");

        CyclePerformancePage page = statsService.getCyclePerformances(USER_ID, null, null, null, null, 0, 10);

        assertThat(page.items().getFirst().pnl()).isEqualByComparingTo("500.00");
        assertThat(page.items().getFirst().returnRate()).isNull();
    }

    @Test
    void 사이클_성과_목록은_계좌와_티커로_필터링한다() {
        UUID otherAccountId = UUID.randomUUID();
        UUID otherStrategyId = UUID.randomUUID();
        Strategy otherAccountStrategy = new Strategy(otherStrategyId, otherAccountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        Account otherAccount = new Account(otherAccountId, USER_ID, "두번째계좌",
                "11112222", "appKey", "appSecret", null, Broker.KIS, null, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(), otherAccount));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID, otherAccountId))).thenReturn(Map.of(
                ACCOUNT_ID, List.of(STRATEGY), otherAccountId, List.of(otherAccountStrategy)));
        StrategyCycle soxl = closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31");
        StrategyCycle tqqq = new StrategyCycle(UUID.randomUUID(), otherStrategyId, null,
                new BigDecimal("1000.00"), new BigDecimal("1200.00"),
                LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-28"),
                Instant.parse("2026-02-01T00:00:00Z"), null);
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(soxl, tqqq));

        assertThat(statsService.getCyclePerformances(USER_ID, null, otherAccountId, null, null, 0, 10).items())
                .extracting(CyclePerformance::cycleId).containsExactly(tqqq.id());
        assertThat(statsService.getCyclePerformances(USER_ID, null, null, StrategyTicker.SOXL, null, 0, 10).items())
                .extracting(CyclePerformance::cycleId).containsExactly(soxl.id());
        // AND 조합 — 계좌·티커 불일치면 빈 결과
        assertThat(statsService.getCyclePerformances(USER_ID, null, ACCOUNT_ID, StrategyTicker.TQQQ, null, 0, 10).items())
                .isEmpty();
        // 타 사용자(본인 소유 아님) 계좌 ID는 빈 결과
        assertThat(statsService.getCyclePerformances(USER_ID, null, UUID.randomUUID(), null, null, 0, 10).items())
                .isEmpty();
    }

    @Test
    void 사이클_성과_목록은_요청한_페이지의_항목만_반환한다() {
        stubUserWithStrategy();
        StrategyCycle c1 = closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31");
        StrategyCycle c2 = closedCycle("1000.00", "1200.00", "2026-02-01", "2026-02-28");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c1, c2));

        CyclePerformancePage second = statsService.getCyclePerformances(USER_ID, null, null, null, null, 1, 1);
        CyclePerformancePage outOfRange = statsService.getCyclePerformances(USER_ID, null, null, null, null, 5, 1);

        assertThat(second.items()).extracting(CyclePerformance::cycleId).containsExactly(c1.id());
        assertThat(second.page()).isEqualTo(1);
        assertThat(outOfRange.items()).isEmpty();
        assertThat(outOfRange.totalElements()).isEqualTo(2);
        assertThat(outOfRange.totalPages()).isEqualTo(2);
    }

    @Test
    void 사이클_성과_목록은_진행_상태로_필터링한다() {
        stubUserWithStrategy();
        StrategyCycle closed = closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31");
        StrategyCycle active = activeCycle(STRATEGY_ID, "1000.00", "2026-02-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(closed, active));

        CyclePerformancePage onlyClosed = statsService.getCyclePerformances(
                USER_ID, null, null, null, CycleStatusFilter.CLOSED, 0, 10);
        CyclePerformancePage onlyActive = statsService.getCyclePerformances(
                USER_ID, null, null, null, CycleStatusFilter.ACTIVE, 0, 10);

        assertThat(onlyClosed.items()).extracting(CyclePerformance::cycleId).containsExactly(closed.id());
        assertThat(onlyClosed.totalElements()).isEqualTo(1);
        assertThat(onlyActive.items()).extracting(CyclePerformance::cycleId).containsExactly(active.id());
        assertThat(onlyActive.totalElements()).isEqualTo(1);
    }

    @Test
    void equityCurve_조회_경계는_KST_자정() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of());
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);

        statsService.getEquityCurve(USER_ID, Currency.USD, null, null, LocalDate.of(2026, 7, 18));

        // to=2026-07-18 → toInstant = 2026-07-19T00:00 KST = 2026-07-18T15:00:00Z
        verify(cyclePositionPort).findByCycleIdsAndRange(any(), any(), toCaptor.capture());
        assertThat(toCaptor.getValue())
                .isEqualTo(LocalDate.of(2026, 7, 19).atStartOfDay(TimeZones.KST).toInstant());
    }

    @Test
    void equity_curve는_스냅샷이_없는_날_직전_스냅샷을_carry_forward한다() {
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID)))
                .thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY, PRIVACY_STRATEGY)));
        StrategyCycle a = activeCycle("1000.00", "2026-06-01");
        StrategyCycle b = activeCycle(PRIVACY_STRATEGY_ID, "2000.00", "2026-06-01"); // 다른 전략 — 같은 createdAt이어도 서로 제외하지 않는다
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(a, b));
        // A는 KST 06-01·06-02 스냅샷, B는 06-01만 → 06-02 포인트에 B의 06-01 값이 carry-forward
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(a.id(), "1000.00", "2026-06-01T01:00:00Z"),
                depositSnapshot(b.id(), "2000.00", "2026-06-01T02:00:00Z"),
                depositSnapshot(a.id(), "1100.00", "2026-06-02T01:00:00Z")));
        EquityCurve curve = statsService.getEquityCurve(USER_ID, Currency.USD, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(2);
        assertThat(curve.points().get(1).date()).isEqualTo(LocalDate.parse("2026-06-02"));
        // A 06-02(1100) + B 06-01 carry-forward(2000)
        assertThat(curve.points().get(1).totalAsset()).isEqualByComparingTo("3100.00");
        assertThat(curve.points().get(1).principal()).isEqualByComparingTo("3000.00");
    }

    @Test
    void equity_curve는_같은_전략의_후속_사이클이_시작한_날부터_옛_사이클을_제외한다() {
        stubUserWithStrategy();
        StrategyCycle ended = closedCycle("1000.00", "1200.00", "2026-05-01", "2026-06-01");
        StrategyCycle active = activeCycle("500.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(ended, active));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(ended.id(), "1200.00", "2026-06-01T01:00:00Z"),
                depositSnapshot(active.id(), "500.00", "2026-06-01T02:00:00Z"),
                depositSnapshot(active.id(), "550.00", "2026-06-02T01:00:00Z")));
        EquityCurve curve = statsService.getEquityCurve(USER_ID, Currency.USD, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(2);
        // 06-01: 교체일 — 후속 사이클만 반영 (이중 합산 방지)
        // 체인 보정: realized 200, surplus max(0, 1200-500)=700 → 자산 500+700, 원금 500-200+700 (손익 0 유지)
        assertThat(curve.points().getFirst().totalAsset()).isEqualByComparingTo("1200.00");
        assertThat(curve.points().getFirst().principal()).isEqualByComparingTo("1000.00");
        // 06-02
        assertThat(curve.points().get(1).totalAsset()).isEqualByComparingTo("1250.00");
        assertThat(curve.points().get(1).principal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void equity_curve는_후속_사이클_createdAt이_같으면_id가_큰_사이클만_남긴다() {
        stubUserWithStrategy();
        Instant sameCreatedAt = Instant.parse("2026-06-01T00:00:00Z");
        UUID lowId = new UUID(0L, 1L);
        UUID highId = new UUID(0L, 2L);
        StrategyCycle low = new StrategyCycle(lowId, STRATEGY_ID, null, new BigDecimal("100.00"), null,
                LocalDate.parse("2026-06-01"), null, sameCreatedAt, null);
        StrategyCycle high = new StrategyCycle(highId, STRATEGY_ID, null, new BigDecimal("200.00"), null,
                LocalDate.parse("2026-06-01"), null, sameCreatedAt, null);
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(low, high));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(lowId, "100.00", "2026-06-01T01:00:00Z"),
                depositSnapshot(highId, "200.00", "2026-06-01T02:00:00Z")));

        EquityCurve curve = statsService.getEquityCurve(USER_ID, Currency.USD, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        // 동률이면 UUID가 큰 쪽만 합산 (이중 합산 방지)
        assertThat(curve.points().getFirst().totalAsset()).isEqualByComparingTo("200.00");
    }

    @Test
    void equity_curve는_후속_사이클이_없는_종료_사이클을_endDate_당일까지_포함한다() {
        stubUserWithStrategy();
        StrategyCycle ended = closedCycle("1000.00", "1200.00", "2026-05-01", "2026-06-02");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(ended));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(ended.id(), "1100.00", "2026-06-01T01:00:00Z"),
                depositSnapshot(ended.id(), "1200.00", "2026-06-02T01:00:00Z"),
                depositSnapshot(UUID.randomUUID(), "1.00", "2026-06-03T01:00:00Z")));
        EquityCurve curve = statsService.getEquityCurve(USER_ID, Currency.USD, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        // 06-03 스냅샷은 모르는 사이클이라 합산 0 — 종료 사이클은 06-02까지만 남는다
        assertThat(curve.points().get(1).totalAsset()).isEqualByComparingTo("1200.00");
        assertThat(curve.points().get(2).totalAsset()).isEqualByComparingTo("0.00");
    }

    @Test
    void equity_curve는_옛_사이클_endDate가_미래여도_새_사이클_스냅샷_날부터_제외한다() {
        stubUserWithStrategy();
        // VR 재설정: 옛 사이클 endDate=06-05(다음 거래일), 새 사이클은 06-02에 시작
        StrategyCycle old = closedCycle("1000.00", "1200.00", "2026-05-01", "2026-06-05");
        StrategyCycle fresh = activeCycle("700.00", "2026-06-02");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(old, fresh));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(old.id(), "1100.00", "2026-06-01T01:00:00Z"),
                depositSnapshot(fresh.id(), "700.00", "2026-06-02T01:00:00Z"),
                depositSnapshot(fresh.id(), "720.00", "2026-06-03T01:00:00Z")));
        EquityCurve curve = statsService.getEquityCurve(USER_ID, Currency.USD, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(3);
        assertThat(curve.points().get(0).totalAsset()).isEqualByComparingTo("1100.00"); // 06-01: 옛 사이클만
        assertThat(curve.points().get(1).totalAsset()).isEqualByComparingTo("1200.00"); // 06-02: 새 700 + surplus 500
        assertThat(curve.points().get(1).principal()).isEqualByComparingTo("1000.00");
        assertThat(curve.points().get(2).totalAsset()).isEqualByComparingTo("1220.00");
    }

    @Test
    void delayedRollover_carriesOldCycleUntilSuccessorSnapshot() {
        stubUserWithStrategy();
        // 지연 롤오버: A는 06-01에 종료, B(후속)의 첫 스냅샷은 06-03 — 06-02는 A를 이어 쓴다
        StrategyCycle a = cycleOf("1000", "1100", "2026-05-01", "2026-06-01");
        StrategyCycle b = cycleOf("1100", null, "2026-06-01", null);
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(a, b));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(a.id(), "1100", "2026-05-31T01:00:00Z"),
                depositSnapshot(UUID.randomUUID(), "1", "2026-06-02T01:00:00Z"), // 06-02 점을 만들기 위한 미지 사이클
                depositSnapshot(b.id(), "1100", "2026-06-03T01:00:00Z")));

        List<EquityPoint> points = statsService.getEquityCurve(USER_ID, Currency.USD, null, null, LocalDate.parse("2026-06-30")).points();

        assertThat(points).hasSize(3);
        assertThat(points.get(1).totalAsset()).isEqualByComparingTo("1100.00");
        assertThat(points.get(1).principal()).isEqualByComparingTo("1000.00");
        // 교체일 연속: B 자산 1100 + surplus 0, 원금 1100 - realized 100 + surplus 0
        assertThat(points.get(2).totalAsset()).isEqualByComparingTo("1100.00");
        assertThat(points.get(2).principal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void endedCycleWithoutSuccessor_dropsAfterEndDate() {
        stubUserWithStrategy();
        StrategyCycle a = cycleOf("1000", "1100", "2026-05-01", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(a));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(a.id(), "1100", "2026-05-31T01:00:00Z"),
                depositSnapshot(UUID.randomUUID(), "1", "2026-06-02T01:00:00Z"))); // 다른 전략 스냅샷으로 06-02 점 생성

        List<EquityPoint> points = statsService.getEquityCurve(USER_ID, Currency.USD, null, null, LocalDate.parse("2026-06-30")).points();

        // 자산에서는 빠지지만 손익 +100은 원금에서 계속 빼 둔다(CQ-70)
        assertThat(points.getLast().totalAsset()).isEqualByComparingTo("0.00");
        assertThat(points.getLast().principal()).isEqualByComparingTo("-100.00");
    }

    // 체인 곡선 검증 — cycles를 생성 순서로 두고 각 사이클 스냅샷 1개씩(deposit)을 날짜별로 준 뒤 마지막 점을 돌려준다
    private EquityPoint lastPointOfChain(List<StrategyCycle> cycles, List<CyclePosition> snaps) {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(cycles);
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(snaps);
        return statsService.getEquityCurve(USER_ID, Currency.USD, null, null, LocalDate.parse("2026-12-31")).points().getLast();
    }

    private static StrategyCycle cycleOf(String start, String end, String startDate, String endDate) {
        return new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, null, new BigDecimal(start),
                end == null ? null : new BigDecimal(end), LocalDate.parse(startDate),
                endDate == null ? null : LocalDate.parse(endDate), Instant.parse(startDate + "T00:00:00Z"), null);
    }

    @Test
    void equity_curve_MAINTAIN_이익_교체_뒤에도_손익_1000이_유지된다() {
        StrategyCycle c1 = cycleOf("10000", "11000", "2026-05-01", "2026-06-01");
        StrategyCycle c2 = cycleOf("10000", null, "2026-06-01", null);
        EquityPoint p = lastPointOfChain(List.of(c1, c2), List.of(
                depositSnapshot(c1.id(), "11000", "2026-06-01T01:00:00Z"),
                depositSnapshot(c2.id(), "10000", "2026-06-02T01:00:00Z")));
        assertThat(p.totalAsset()).isEqualByComparingTo("11000");
        assertThat(p.principal()).isEqualByComparingTo("10000");
    }

    @Test
    void equity_curve_MAX_이익_교체_뒤에도_원금선이_리셋되지_않는다() {
        StrategyCycle c1 = cycleOf("10000", "11000", "2026-05-01", "2026-06-01");
        StrategyCycle c2 = cycleOf("11000", null, "2026-06-01", null);
        EquityPoint p = lastPointOfChain(List.of(c1, c2), List.of(
                depositSnapshot(c1.id(), "11000", "2026-06-01T01:00:00Z"),
                depositSnapshot(c2.id(), "11000", "2026-06-02T01:00:00Z")));
        assertThat(p.totalAsset()).isEqualByComparingTo("11000");
        assertThat(p.principal()).isEqualByComparingTo("10000");
    }

    @Test
    void equity_curve_MAINTAIN_손실_교체_뒤에도_손실_1000이_유지된다() {
        StrategyCycle c1 = cycleOf("10000", "9000", "2026-05-01", "2026-06-01");
        StrategyCycle c2 = cycleOf("9000", null, "2026-06-01", null);
        EquityPoint p = lastPointOfChain(List.of(c1, c2), List.of(
                depositSnapshot(c1.id(), "9000", "2026-06-01T01:00:00Z"),
                depositSnapshot(c2.id(), "9000", "2026-06-02T01:00:00Z")));
        assertThat(p.totalAsset()).isEqualByComparingTo("9000");
        assertThat(p.principal()).isEqualByComparingTo("10000");
    }

    @Test
    void equity_curve_VR_롤오버는_원금에_적립금을_더한다() {
        StrategyCycle c1 = cycleOf("10000", "12000", "2026-05-01", "2026-06-01");
        StrategyCycle c2 = cycleOf("12500", null, "2026-06-01", null); // recurring 500
        EquityPoint p = lastPointOfChain(List.of(c1, c2), List.of(
                depositSnapshot(c1.id(), "12000", "2026-06-01T01:00:00Z"),
                depositSnapshot(c2.id(), "12500", "2026-06-02T01:00:00Z")));
        assertThat(p.principal()).isEqualByComparingTo("10500");
        assertThat(p.totalAsset()).isEqualByComparingTo("12500");
    }

    @Test
    void equity_curve_사이클_3개_체인은_이익_뒤_손실을_누적한다() {
        StrategyCycle c1 = cycleOf("10000", "11000", "2026-05-01", "2026-06-01"); // +1000, 다음 10000(MAINTAIN)
        StrategyCycle c2 = cycleOf("10000", "9500", "2026-06-01", "2026-07-01");  // -500, 다음 9500
        StrategyCycle c3 = cycleOf("9500", null, "2026-07-01", null);
        EquityPoint p = lastPointOfChain(List.of(c1, c2, c3), List.of(
                depositSnapshot(c1.id(), "11000", "2026-06-01T01:00:00Z"),
                depositSnapshot(c2.id(), "9500", "2026-07-01T01:00:00Z"),
                depositSnapshot(c3.id(), "9500", "2026-07-02T01:00:00Z")));
        // surplus 합 1000, realized 합 500 → 자산 9500+1000, 원금 9500-500+1000 → 손익 +500
        assertThat(p.totalAsset()).isEqualByComparingTo("10500");
        assertThat(p.principal()).isEqualByComparingTo("10000");
    }

    @Test
    void equity_curve_체인의_마지막_사이클이_종료되면_자산에서_빠지고_체인_손익만_남는다() {
        StrategyCycle c1 = cycleOf("10000", "11000", "2026-05-01", "2026-06-01");
        StrategyCycle c2 = cycleOf("10000", "10500", "2026-06-01", "2026-06-10");
        EquityPoint p = lastPointOfChain(List.of(c1, c2), List.of(
                depositSnapshot(c1.id(), "11000", "2026-06-01T01:00:00Z"),
                depositSnapshot(c2.id(), "10500", "2026-06-10T01:00:00Z"),
                depositSnapshot(UUID.randomUUID(), "1", "2026-06-11T01:00:00Z"))); // 06-11 점을 만들기 위한 미지 사이클
        // 체인 손익 +1000 +500 = 1500을 원금에서 계속 빼 둔다(CQ-70) — surplus는 전략과 함께 빠진다
        assertThat(p.totalAsset()).isEqualByComparingTo("0");
        assertThat(p.principal()).isEqualByComparingTo("-1500");
    }

    @Test
    void equity_curve는_다른_전략의_사이클끼리_서로_제외하지_않는다() {
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID)))
                .thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY, PRIVACY_STRATEGY)));
        StrategyCycle a = activeCycle("500.00", "2026-06-01");
        StrategyCycle b = activeCycle(PRIVACY_STRATEGY_ID, "300.00", "2026-06-02");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(a, b));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(a.id(), "500.00", "2026-06-01T01:00:00Z"),
                depositSnapshot(b.id(), "300.00", "2026-06-02T01:00:00Z")));
        EquityCurve curve = statsService.getEquityCurve(USER_ID, Currency.USD, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points().get(1).totalAsset()).isEqualByComparingTo("800.00");
        assertThat(curve.points().get(1).principal()).isEqualByComparingTo("800.00");
    }

    @Test
    void 자본_변경_이벤트는_트랜잭션_동기화가_활성이면_커밋_시점에_캐시를_비운다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("500.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "500.00", "2026-06-01T01:00:00Z")));
        LocalDate from = LocalDate.parse("2026-06-01");
        LocalDate to = LocalDate.parse("2026-06-30");
        statsService.getEquityCurve(USER_ID, Currency.USD, null, from, to);

        TransactionSynchronizationManager.initSynchronization();
        try {
            statsService.onStrategyCapitalChanged(new StrategyCapitalChangedEvent(USER_ID));
            statsService.getEquityCurve(USER_ID, Currency.USD, null, from, to); // 커밋 전 — 아직 캐시 적중
            verify(cyclePositionPort, times(1)).findByCycleIdsAndRange(any(), any(), any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        statsService.getEquityCurve(USER_ID, Currency.USD, null, from, to); // 커밋 후 — 재계산
        verify(cyclePositionPort, times(2)).findByCycleIdsAndRange(any(), any(), any());
    }

    @Test
    void 자본_변경_이벤트는_트랜잭션이_없으면_즉시_해당_사용자의_캐시를_비운다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("500.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "500.00", "2026-06-01T01:00:00Z")));
        LocalDate from = LocalDate.parse("2026-06-01");
        LocalDate to = LocalDate.parse("2026-06-30");

        statsService.getEquityCurve(USER_ID, Currency.USD, null, from, to);
        statsService.getEquityCurve(USER_ID, Currency.USD, null, from, to); // 캐시 적중
        verify(cyclePositionPort, times(1)).findByCycleIdsAndRange(any(), any(), any());

        statsService.onStrategyCapitalChanged(new StrategyCapitalChangedEvent(USER_ID));
        statsService.getEquityCurve(USER_ID, Currency.USD, null, from, to);
        verify(cyclePositionPort, times(2)).findByCycleIdsAndRange(any(), any(), any());
    }

    private static final Strategy DCA_STRATEGY = new Strategy(UUID.randomUUID(), ACCOUNT_ID, StrategyType.DCA,
            StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);

    private void stubDcaOnly(StrategyCycle cycle) {
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(DCA_STRATEGY)));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
    }

    // 자산 = 보유 수량 × 종가 (deposit 0)
    private static CyclePosition holdingSnapshot(UUID cycleId, String closing, String createdAt) {
        return holdingSnapshot(cycleId, closing, BigDecimal.ONE, createdAt);
    }

    private static CyclePosition holdingSnapshot(UUID cycleId, String closing, BigDecimal holdings, String createdAt) {
        return new CyclePosition(UUID.randomUUID(), cycleId, BigDecimal.ZERO, new BigDecimal(closing),
                new BigDecimal(closing), holdings, Instant.parse(createdAt), null);
    }

    @Test
    void 요약은_DCA_전략_사이클도_포함한다() {
        StrategyCycle cycle = activeCycle(DCA_STRATEGY.id(), "100", "2026-06-01");
        stubDcaOnly(cycle);
        when(cyclePositionPort.findLatestByCycleIds(Set.of(cycle.id())))
                .thenReturn(Map.of(cycle.id(), holdingSnapshot(cycle.id(), "130.00", "2026-06-01T01:00:00Z")));

        StatsSummary summary = statsService.getSummary(USER_ID, Currency.USD);

        assertThat(summary.byType()).extracting(StrategyTypeStats::type).containsExactly(StrategyType.DCA);
        assertThat(summary.activePrincipal()).isEqualByComparingTo("100.00"); // 시드 = 시작 원금
        assertThat(summary.totalUnrealizedPnl()).isEqualByComparingTo("30.00"); // 평가 130 - 원금 100
    }

    // 통화별 합산 검증용 — KIS(USD)·BINANCE(USDT)·UPBIT(KRW)·MOCK 계좌 각 1개에 진행 중 DCA 사이클을 두고 원금을 100/200/300/400으로 구분한다
    private record MultiCurrency(Account kis, Account binance, Account upbit, Account mock,
                                 Map<UUID, StrategyCycle> cycleByAccount) {}

    private MultiCurrency stubMultiCurrencyUser() {
        Account kis = testAccount();
        Account binance = new Account(UUID.randomUUID(), USER_ID, "바이낸스", null, "k", "s", null, Broker.BINANCE, null, null);
        Account upbit = new Account(UUID.randomUUID(), USER_ID, "업비트", "n", "k", "s", null, Broker.UPBIT, null, null);
        Account mock = new Account(UUID.randomUUID(), USER_ID, "모의", "0", "k", "s", null, Broker.MOCK, null, null);
        Map<UUID, Account> accounts = new java.util.LinkedHashMap<>();
        Map<UUID, StrategyCycle> cycleByAccount = new java.util.LinkedHashMap<>();
        Map<UUID, Strategy> strategyByAccount = new java.util.LinkedHashMap<>();
        String[] principals = {"100", "200", "300", "400"};
        int i = 0;
        for (Account a : List.of(kis, binance, upbit, mock)) {
            Strategy s = new Strategy(UUID.randomUUID(), a.id(), StrategyType.DCA, StrategyStatus.ACTIVE,
                    StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
            accounts.put(a.id(), a);
            strategyByAccount.put(a.id(), s);
            cycleByAccount.put(a.id(), activeCycle(s.id(), principals[i++], "2026-06-01"));
        }
        lenient().when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(kis, binance, upbit, mock));
        lenient().when(strategyPort.findByAccountIds(any())).thenAnswer(inv -> {
            Map<UUID, List<Strategy>> result = new java.util.HashMap<>();
            for (UUID id : (java.util.Collection<UUID>) inv.getArgument(0)) result.put(id, List.of(strategyByAccount.get(id)));
            return result;
        });
        lenient().when(strategyCyclePort.findByStrategyIds(any())).thenAnswer(inv -> {
            java.util.Collection<UUID> ids = inv.getArgument(0);
            return cycleByAccount.values().stream().filter(c -> ids.contains(c.strategyId())).toList();
        });
        return new MultiCurrency(kis, binance, upbit, mock, cycleByAccount);
    }

    @Test
    void 합산_경로는_요청_통화_계좌만_포함하고_사이클_성과_목록은_전부_포함한다() {
        MultiCurrency m = stubMultiCurrencyUser();

        assertThat(statsService.getSummary(USER_ID, Currency.USD).activePrincipal()).isEqualByComparingTo("100");
        assertThat(statsService.getSummary(USER_ID, Currency.USDT).activePrincipal()).isEqualByComparingTo("200");
        assertThat(statsService.getSummary(USER_ID, Currency.KRW).activePrincipal()).isEqualByComparingTo("300");
        assertThat(statsService.getCyclePerformances(USER_ID, null, null, null, null, 0, 10).items())
                .extracting(CyclePerformance::accountId)
                .containsExactlyInAnyOrder(m.kis().id(), m.binance().id(), m.upbit().id(), m.mock().id());
    }

    @Test
    void equityCurve는_요청_통화_계좌만_합산한다() {
        MultiCurrency m = stubMultiCurrencyUser();
        StrategyCycle binanceCycle = m.cycleByAccount().get(m.binance().id());
        when(cyclePositionPort.findByCycleIdsAndRange(eq(Set.of(binanceCycle.id())), any(), any())).thenReturn(List.of(
                depositSnapshot(binanceCycle.id(), "200.00", "2026-06-02T01:00:00Z")));

        EquityCurve curve = statsService.getEquityCurve(USER_ID, Currency.USDT, null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(1);
        assertThat(curve.points().getFirst().totalAsset()).isEqualByComparingTo("200.00");
    }

    @Test
    void availableCurrencies는_요청_통화와_무관하다() {
        // BINANCE만 가진 사용자가 USD로 요청 — 합계 0·byType 비어 있고 사용 통화는 [USDT]
        Account binance = new Account(UUID.randomUUID(), USER_ID, "바이낸스", null, "k", "s", null, Broker.BINANCE, null, null);
        Strategy s = new Strategy(UUID.randomUUID(), binance.id(), StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(binance));
        when(strategyPort.findByAccountIds(List.of(binance.id()))).thenReturn(Map.of(binance.id(), List.of(s)));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(activeCycle(s.id(), "200", "2026-06-01")));

        StatsSummary usd = statsService.getSummary(USER_ID, Currency.USD);

        assertThat(usd.byType()).isEmpty();
        assertThat(usd.totalRealizedPnl()).isEqualByComparingTo("0");
        assertThat(usd.availableCurrencies()).containsExactly(Currency.USDT);

        // 4계좌 사용자 — enum 선언 순서, MOCK 제외
        reset(accountPort, strategyPort, strategyCyclePort);
        stubMultiCurrencyUser();
        assertThat(statsService.getSummary(USER_ID, Currency.KRW).availableCurrencies())
                .containsExactly(Currency.KRW, Currency.USD, Currency.USDT);
    }

    @Test
    void availableCurrencies는_MOCK_계좌를_제외한다() {
        // MOCK·BINANCE 계좌 각각 사이클 보유 — 사용 통화는 [USDT]만
        Account mock = new Account(UUID.randomUUID(), USER_ID, "모의", "0", "k", "s", null, Broker.MOCK, null, null);
        Account binance = new Account(UUID.randomUUID(), USER_ID, "바이낸스", null, "k", "s", null, Broker.BINANCE, null, null);
        Strategy mockStrategy = new Strategy(UUID.randomUUID(), mock.id(), StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        Strategy binanceStrategy = new Strategy(UUID.randomUUID(), binance.id(), StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(mock, binance));
        // 실제 포트처럼 요청한 id의 전략·사이클만 돌려준다
        Map<UUID, List<Strategy>> strategyByAccount = Map.of(
                mock.id(), List.of(mockStrategy), binance.id(), List.of(binanceStrategy));
        List<StrategyCycle> allCycles = List.of(
                activeCycle(mockStrategy.id(), "100", "2026-06-01"),
                activeCycle(binanceStrategy.id(), "200", "2026-06-01"));
        when(strategyPort.findByAccountIds(any())).thenAnswer(inv -> {
            Map<UUID, List<Strategy>> result = new java.util.HashMap<>();
            for (UUID id : (java.util.Collection<UUID>) inv.getArgument(0)) result.put(id, strategyByAccount.get(id));
            return result;
        });
        when(strategyCyclePort.findByStrategyIds(any())).thenAnswer(inv -> {
            java.util.Collection<UUID> ids = inv.getArgument(0);
            return allCycles.stream().filter(c -> ids.contains(c.strategyId())).toList();
        });

        assertThat(statsService.getSummary(USER_ID, Currency.USD).availableCurrencies())
                .containsExactly(Currency.USDT);
    }

    @Test
    void summary_캐시는_통화별로_분리된다() {
        stubMultiCurrencyUser();

        StatsSummary usd = statsService.getSummary(USER_ID, Currency.USD);
        StatsSummary usdt = statsService.getSummary(USER_ID, Currency.USDT);
        statsService.getSummary(USER_ID, Currency.USD); // 캐시 적중
        statsService.getSummary(USER_ID, Currency.USDT); // 캐시 적중

        assertThat(usd.activePrincipal()).isNotEqualByComparingTo(usdt.activePrincipal());
        verify(accountPort, times(2)).findByUserId(USER_ID); // 통화당 1회
    }

    @Test
    void 요약_전략유형비교는_모의계좌_사이클을_제외한다() {
        UUID mockAccountId = UUID.randomUUID();
        Account mockAccount = new Account(mockAccountId, USER_ID, "모의계좌",
                "00000000", "key", "secret", null, Broker.MOCK, null, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(), mockAccount));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY)));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31")));

        StatsSummary summary = statsService.getSummary(USER_ID, Currency.USD);

        assertThat(summary.totalRealizedPnl()).isEqualByComparingTo("100.00");
        assertThat(summary.byType()).hasSize(1);
        verify(strategyPort, never()).findByAccountIds(argThat(ids -> ids.contains(mockAccountId)));
    }

    @Test
    void 누적자산추이는_모의계좌_사이클을_제외한다() {
        UUID mockAccountId = UUID.randomUUID();
        Account mockAccount = new Account(mockAccountId, USER_ID, "모의계좌",
                "00000000", "key", "secret", null, Broker.MOCK, null, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(), mockAccount));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY)));
        StrategyCycle active = activeCycle("1000.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(active.id(), "1000.00", "2026-06-02T01:00:00Z")));

        EquityCurve curve = statsService.getEquityCurve(USER_ID, Currency.USD, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(1);
        verify(strategyPort, never()).findByAccountIds(argThat(ids -> ids.contains(mockAccountId)));
    }

    @Test
    void 사이클_성과_목록은_모의계좌_사이클도_포함한다() {
        UUID mockAccountId = UUID.randomUUID();
        Account mockAccount = new Account(mockAccountId, USER_ID, "모의계좌",
                "00000000", "key", "secret", null, Broker.MOCK, null, null);
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

        CyclePerformancePage page = statsService.getCyclePerformances(USER_ID, null, null, null, null, 0, 10);

        assertThat(page.items()).extracting(CyclePerformance::accountId).containsExactly(mockAccountId);
    }

    // ── 결과 캐시(TTL 5분) ───────────────────────────────────────────────────

    @Test
    void getSummary는_TTL_내_재조회시_캐시를_반환하고_DB를_다시_조회하지_않는다() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31")));

        StatsSummary first = statsService.getSummary(USER_ID, Currency.USD);
        StatsSummary second = statsService.getSummary(USER_ID, Currency.USD);

        assertThat(second).isEqualTo(first);
        verify(accountPort, times(1)).findByUserId(USER_ID);
        verify(strategyCyclePort, times(1)).findByStrategyIds(any());
    }

    @Test
    void 매매_리포트_이벤트는_해당_사용자의_summary와_equityCurve_캐시만_비운다() {
        stubUserWithStrategy();
        StrategyCycle active = activeCycle("1000.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(active.id(), "1000.00", "2026-06-02T01:00:00Z")));
        LocalDate from = LocalDate.parse("2026-06-01");
        LocalDate to = LocalDate.parse("2026-06-30");

        statsService.getSummary(USER_ID, Currency.USD);
        statsService.getEquityCurve(USER_ID, Currency.USD, null, from, to);
        statsService.onTradingReportReady(reportEvent(UUID.randomUUID())); // 다른 사용자 — 캐시 유지
        statsService.getSummary(USER_ID, Currency.USD);
        statsService.getEquityCurve(USER_ID, Currency.USD, null, from, to);
        statsService.onTradingReportReady(reportEvent(USER_ID));
        statsService.getSummary(USER_ID, Currency.USD);
        statsService.getEquityCurve(USER_ID, Currency.USD, null, from, to);

        verify(accountPort, times(4)).findByUserId(USER_ID); // summary·curve 각 2회(무효화 전후)
        verify(cyclePositionPort, times(4)).findByCycleIdsAndRange(any(), any(), any()); // summary(진행 중 실현)·curve 무효화 전후 각 1회
    }

    private static TradingReportReadyEvent reportEvent(UUID userId) {
        return new TradingReportReadyEvent(userId, ACCOUNT_ID, STRATEGY_ID, "계좌", null, List.of(), true);
    }

    @Test
    void getEquityCurve는_파라미터별로_캐시_키가_분리된다() {
        stubUserWithStrategy();
        StrategyCycle active = activeCycle("1000.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(active.id(), "1000.00", "2026-06-02T01:00:00Z")));

        // 같은 파라미터 2회 → 캐시 1회 조회
        statsService.getEquityCurve(USER_ID, Currency.USD, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        statsService.getEquityCurve(USER_ID, Currency.USD, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
        // to가 다른 호출 → 별도 캐시 키로 재조회
        statsService.getEquityCurve(USER_ID, Currency.USD, null, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-07-31"));

        verify(cyclePositionPort, times(2)).findByCycleIdsAndRange(any(), any(), any());
    }

    // ── 투자금 이력 소비 ─────────────────────────────────────────────────────

    private static final Strategy VR_STRATEGY = new Strategy(UUID.randomUUID(), ACCOUNT_ID, StrategyType.VR,
            StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);

    private void stubUserWith(Strategy strategy) {
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(strategy)));
    }

    private static CyclePosition position(UUID id, UUID cycleId, String deposit, String closing, String avg,
                                          int holdings, String createdAt) {
        return new CyclePosition(id, cycleId, new BigDecimal(deposit),
                closing != null ? new BigDecimal(closing) : null, avg != null ? new BigDecimal(avg) : null,
                BigDecimal.valueOf(holdings), Instant.parse(createdAt), null);
    }

    private static CycleCapitalEvent injectEvent(UUID cycleId, UUID positionId, String deposit, String occurredAt) {
        return new CycleCapitalEvent(UUID.randomUUID(), cycleId, positionId, CycleCapitalEvent.Kind.INJECT,
                new BigDecimal(deposit), BigDecimal.ZERO, new BigDecimal(deposit), Instant.parse(occurredAt));
    }

    private static StrategyCycle closedCycleOf(Strategy strategy, String start, String end) {
        return new StrategyCycle(UUID.randomUUID(), strategy.id(), null, new BigDecimal(start), new BigDecimal(end),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void equityCurve_capitalInjection_keepsPastPrincipalAndAsset() {
        stubUserWith(PRIVACY_STRATEGY);
        StrategyCycle active = cycleWithOpen(PRIVACY_STRATEGY_ID, "15000.00", "10000.00", null); // 개장 평가 10,000 + 이후 추가 5,000
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));
        UUID p2 = UUID.randomUUID();
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                position(UUID.randomUUID(), active.id(), "10000", null, null, 0, "2026-06-01T03:00:00Z"),
                position(p2, active.id(), "15000", null, null, 0, "2026-06-10T03:00:00Z")));
        // 06-20 KST에 +5,000 추가 — P2는 현재값(15,000)이라 원본 10,000으로 되돌려야 한다
        when(cycleCapitalEventPort.findByCycleIds(any())).thenReturn(List.of(
                injectEvent(active.id(), p2, "5000", "2026-06-20T03:00:00Z")));

        List<EquityPoint> points = statsService.getEquityCurve(USER_ID, Currency.USD, null, null, LocalDate.parse("2026-06-30")).points();

        assertThat(points).extracting(EquityPoint::date)
                .containsExactly(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-10"), LocalDate.parse("2026-06-20"));
        assertThat(points.get(0).totalAsset()).isEqualByComparingTo("10000");
        assertThat(points.get(0).principal()).isEqualByComparingTo("10000");
        assertThat(points.get(1).totalAsset()).isEqualByComparingTo("10000");
        assertThat(points.get(1).principal()).isEqualByComparingTo("10000");
        assertThat(points.get(2).totalAsset()).isEqualByComparingTo("15000");
        assertThat(points.get(2).principal()).isEqualByComparingTo("15000");
    }

    @Test
    void 시드_수정_후_자산추이_과거_원금선이_바뀌지_않음() {
        stubUserWithStrategy();
        StrategyCycle active = cycleWithOpen(STRATEGY_ID, "1500.00", "1000.00", null); // 시작일 2026-01-01
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));
        UUID row = UUID.randomUUID();
        // 단일 행이 시드 수정으로 현재값(1,500)이 됐다
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                position(row, active.id(), "1500", null, null, 0, "2026-01-01T03:00:00Z")));
        when(cycleCapitalEventPort.findByCycleIds(any())).thenReturn(List.of(
                new CycleCapitalEvent(UUID.randomUUID(), active.id(), row, CycleCapitalEvent.Kind.RESET,
                        new BigDecimal("500"), BigDecimal.ZERO, new BigDecimal("500"),
                        Instant.parse("2026-01-03T03:00:00Z"))));

        List<EquityPoint> points = statsService.getEquityCurve(USER_ID, Currency.USD, null, null, LocalDate.parse("2026-01-05")).points();

        assertThat(points).extracting(EquityPoint::date)
                .containsExactly(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-03"));
        assertThat(points.get(0).principal()).isEqualByComparingTo("1000");
        assertThat(points.get(0).totalAsset()).isEqualByComparingTo("1000");
        assertThat(points.get(1).principal()).isEqualByComparingTo("1500");
        assertThat(points.get(1).totalAsset()).isEqualByComparingTo("1500");
    }

    @Test
    void equityCurve_chainSurplus_usesStartAmountAtRotation() {
        stubUserWithStrategy();
        StrategyCycle c1 = closedCycle("10000.00", "12000.00", "2026-06-01", "2026-06-10");
        StrategyCycle c2 = new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, null, new BigDecimal("13000.00"), new BigDecimal("11000.00"),
                null, LocalDate.parse("2026-06-10"), null, Instant.parse("2026-06-10T00:00:00Z"), null); // 현재 13,000 = 개장 11,000 + 이후 추가 2,000
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c1, c2));
        UUID row = UUID.randomUUID();
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                position(UUID.randomUUID(), c1.id(), "11000", null, null, 0, "2026-06-05T03:00:00Z"),
                position(row, c2.id(), "13000", null, null, 0, "2026-06-10T03:00:00Z")));
        when(cycleCapitalEventPort.findByCycleIds(any())).thenReturn(List.of(
                injectEvent(c2.id(), row, "2000", "2026-06-20T03:00:00Z")));

        List<EquityPoint> points = statsService.getEquityCurve(USER_ID, Currency.USD, null, null, LocalDate.parse("2026-06-30")).points();

        // surplus = 12,000 - 11,000 = 1,000, realized = 2,000
        assertThat(points).hasSize(3);
        assertThat(points.get(1).totalAsset()).isEqualByComparingTo("12000");
        assertThat(points.get(1).principal()).isEqualByComparingTo("10000");
        assertThat(points.get(2).totalAsset()).isEqualByComparingTo("14000");
        assertThat(points.get(2).principal()).isEqualByComparingTo("12000");
    }

    // 종료 사이클의 전체 포지션 행 스텁 — summary는 진행 중 사이클까지 한 번에 조회하므로 id 집합은 가리지 않는다
    private void stubClosedSnapshots(StrategyCycle closed, CyclePosition... rows) {
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(rows));
    }

    // 개장 시가 평가(openAmount)가 시작 금액과 다른 사이클
    private static StrategyCycle cycleWithOpen(UUID strategyId, String start, String open, String end) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, null, new BigDecimal(start), new BigDecimal(open),
                end == null ? null : new BigDecimal(end), LocalDate.parse("2026-01-01"),
                end == null ? null : LocalDate.parse("2026-02-01"), Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    private static CyclePosition pos(UUID cycleId, String deposit, String closing, String avg, int holdings, String createdAt) {
        return position(UUID.randomUUID(), cycleId, deposit, closing, avg, holdings, createdAt);
    }

    @Test
    void summary_privacyMidStart_principalIsOpenAmount() {
        stubUserWith(PRIVACY_STRATEGY);
        StrategyCycle c = cycleWithOpen(PRIVACY_STRATEGY_ID, "66156.02", "38289.77", null);
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c));
        when(cyclePositionPort.findLatestByCycleIds(Set.of(c.id()))).thenReturn(Map.of(c.id(),
                new CyclePosition(UUID.randomUUID(), c.id(), new BigDecimal("27869.33"), null, null,
                        BigDecimal.ZERO, Instant.parse("2026-02-01T03:00:00Z"), null)));

        StatsSummary summary = statsService.getSummary(USER_ID, Currency.USD);
        CyclePerformance perf = statsService.getCyclePerformances(USER_ID, null, null, null, null, 0, 10).items().getFirst();

        assertThat(summary.totalUnrealizedPnl()).isEqualByComparingTo("-10420.44");
        assertThat(summary.activePrincipal()).isEqualByComparingTo("38289.77");
        assertThat(summary.totalRealizedPnl().add(summary.totalUnrealizedPnl())).isEqualByComparingTo("-10420.44");
        assertThat(perf.startAmount()).isEqualByComparingTo("38289.77");
        assertThat(perf.returnRate().doubleValue()).isCloseTo(-0.2721, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void equityCurve_privacyMidStart_principalIsOpenAmount() {
        stubUserWith(PRIVACY_STRATEGY);
        StrategyCycle c = cycleWithOpen(PRIVACY_STRATEGY_ID, "66156.02", "38289.77", null);
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                pos(c.id(), "27869.33", null, null, 0, "2026-02-01T03:00:00Z")));

        EquityPoint p = statsService.getEquityCurve(USER_ID, Currency.USD, null, null, LocalDate.parse("2026-02-28")).points().getLast();

        assertThat(p.principal()).isEqualByComparingTo("38289.77");
        assertThat(p.totalAsset()).isEqualByComparingTo("27869.33");
    }

    @Test
    void summary_carriedHoldingsFullySold_realizedIsMarketOpenBasis() {
        stubUserWith(VR_STRATEGY);
        // 개장: 예수금 100 + 10주(평단 50, 종가 62) = 720 → 전량 70에 매도해 800
        StrategyCycle c = cycleWithOpen(VR_STRATEGY.id(), "720", "720", "800");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c));
        stubClosedSnapshots(c,
                pos(c.id(), "100", "62", "50", 10, "2026-01-01T03:00:00Z"),
                pos(c.id(), "450", "70", "50", 5, "2026-01-10T03:00:00Z"),
                pos(c.id(), "800", "70", null, 0, "2026-02-01T03:00:00Z"));

        StrategyTypeStats vr = statsService.getSummary(USER_ID, Currency.USD).byType().getFirst();

        assertThat(vr.realizedPnl()).isEqualByComparingTo("80.00"); // 장부(평단) 기준 280이 아니다
        assertThat(vr.unrealizedPnl()).isEqualByComparingTo("0.00");
    }

    @Test
    void summary_registeredAvgDiffersFromMarket_noTrade_realizedZero() {
        stubUserWith(VR_STRATEGY);
        StrategyCycle c = cycleWithOpen(VR_STRATEGY.id(), "720", "720", "750");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c));
        stubClosedSnapshots(c,
                pos(c.id(), "100", "62", "50", 10, "2026-01-01T03:00:00Z"),
                pos(c.id(), "100", "65", "50", 10, "2026-02-01T03:00:00Z"));

        StrategyTypeStats vr = statsService.getSummary(USER_ID, Currency.USD).byType().getFirst();

        assertThat(vr.realizedPnl()).isEqualByComparingTo("0.00");
        assertThat(vr.unrealizedPnl()).isEqualByComparingTo("30.00");
    }

    @Test
    void summary_registeredAvgDiffersFromMarket_partialSell_releasesPremiumProportionally() {
        stubUserWith(VR_STRATEGY);
        StrategyCycle c = cycleWithOpen(VR_STRATEGY.id(), "720", "720", "780");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c));
        stubClosedSnapshots(c,
                pos(c.id(), "100", "62", "50", 10, "2026-01-01T03:00:00Z"),
                pos(c.id(), "450", "66", "50", 5, "2026-02-01T03:00:00Z")); // 5주를 70에 매도

        StrategyTypeStats vr = statsService.getSummary(USER_ID, Currency.USD).byType().getFirst();

        assertThat(vr.realizedPnl()).isEqualByComparingTo("40.00"); // 5 x (70 - 62)
        assertThat(vr.unrealizedPnl()).isEqualByComparingTo("20.00");
        assertThat(vr.realizedPnl().add(vr.unrealizedPnl())).isEqualByComparingTo("60.00");
    }

    @Test
    void summary_vrRollover_splitsRealizedAndUnrealized() {
        stubUserWith(VR_STRATEGY);
        StrategyCycle c1 = closedCycleOf(VR_STRATEGY, "10000", "12200");
        StrategyCycle c2 = activeCycle(VR_STRATEGY.id(), "12200", "2026-02-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c1, c2));
        stubClosedSnapshots(c1,
                position(UUID.randomUUID(), c1.id(), "5000", "100", "100", 50, "2026-01-01T03:00:00Z"),
                position(UUID.randomUUID(), c1.id(), "6200", "100", "100", 40, "2026-02-01T03:00:00Z"));
        when(cyclePositionPort.findLatestByCycleIds(Set.of(c2.id()))).thenReturn(Map.of(c2.id(),
                position(UUID.randomUUID(), c2.id(), "6200", "160", "100", 40, "2026-02-02T03:00:00Z")));

        StrategyTypeStats vr = statsService.getSummary(USER_ID, Currency.USD).byType().getFirst();

        // 장부가 실현 = 10,200 - 10,000, 미실현 = 합 2,600 - 200
        assertThat(vr.realizedPnl()).isEqualByComparingTo("200.00");
        assertThat(vr.unrealizedPnl()).isEqualByComparingTo("2400.00");
    }

    @Test
    void summary_vrRollover_roundsRealizedToTwoDecimalsAndKeepsTotal() {
        stubUserWith(VR_STRATEGY);
        StrategyCycle c1 = closedCycleOf(VR_STRATEGY, "10000", "12200");
        StrategyCycle c2 = activeCycle(VR_STRATEGY.id(), "12200", "2026-02-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c1, c2));
        // 마지막 평단이 소수 4자리라 장부가 실현 = 10204.936 - 10000 = 204.936
        stubClosedSnapshots(c1,
                position(UUID.randomUUID(), c1.id(), "5000", "100", "100", 50, "2026-01-01T03:00:00Z"),
                position(UUID.randomUUID(), c1.id(), "6200", "100", "100.1234", 40, "2026-02-01T03:00:00Z"));
        when(cyclePositionPort.findLatestByCycleIds(Set.of(c2.id()))).thenReturn(Map.of(c2.id(),
                position(UUID.randomUUID(), c2.id(), "6200", "160", "100", 40, "2026-02-02T03:00:00Z")));

        StrategyTypeStats vr = statsService.getSummary(USER_ID, Currency.USD).byType().getFirst();

        assertThat(vr.realizedPnl().scale()).isEqualTo(2);
        assertThat(vr.realizedPnl()).isEqualTo(new BigDecimal("204.94"));
        // 합(2,600)은 반올림 전후 모두 보존
        assertThat(vr.realizedPnl().add(vr.unrealizedPnl())).isEqualByComparingTo("2600");
    }

    @Test
    void summary_vrReconfigureWithoutSell_realizedZero() {
        stubUserWith(VR_STRATEGY);
        StrategyCycle c1 = closedCycleOf(VR_STRATEGY, "10000", "10500");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c1));
        stubClosedSnapshots(c1,
                position(UUID.randomUUID(), c1.id(), "5000", "100", "100", 50, "2026-01-01T03:00:00Z"),
                position(UUID.randomUUID(), c1.id(), "5000", "110", "100", 50, "2026-02-01T03:00:00Z"));

        StrategyTypeStats vr = statsService.getSummary(USER_ID, Currency.USD).byType().getFirst();

        assertThat(vr.realizedPnl()).isEqualByComparingTo("0.00");
        assertThat(vr.unrealizedPnl()).isEqualByComparingTo("500.00");
    }

    @Test
    void summary_liquidatedWithInitialHoldings_realizedIsEndMinusStart() {
        stubUserWith(PRIVACY_STRATEGY);
        StrategyCycle c1 = closedCycleOf(PRIVACY_STRATEGY, "10000", "11000");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c1));
        stubClosedSnapshots(c1,
                position(UUID.randomUUID(), c1.id(), "4000", "80", "60", 100, "2026-01-01T03:00:00Z"),
                position(UUID.randomUUID(), c1.id(), "11000", null, null, 0, "2026-02-01T03:00:00Z"));

        StrategyTypeStats privacy = statsService.getSummary(USER_ID, Currency.USD).byType().getFirst();

        assertThat(privacy.realizedPnl()).isEqualByComparingTo("1000.00");
        assertThat(privacy.unrealizedPnl()).isEqualByComparingTo("0.00");
    }

    @Test
    void cyclePerformances_unchangedForVrRollover() {
        stubUserWith(VR_STRATEGY);
        StrategyCycle c1 = closedCycleOf(VR_STRATEGY, "10000", "12200");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c1));

        CyclePerformance perf = statsService.getCyclePerformances(USER_ID, null, null, null, null, 0, 10).items().getFirst();

        assertThat(perf.pnl()).isEqualByComparingTo("2200");
    }

    // ── CQ-68 진행 중 사이클 매도 확정분 실현 ─────────────────────────────

    // INFINITE 진행 중: 개장 현금 1000 → 10주 50 매수 → 쿼터매도 5주 60 (최신 종가 58)
    private StrategyCycle stubActiveInfiniteQuarterSell() {
        StrategyCycle active = activeCycle("1000.00", "2026-06-01");
        CyclePosition latest = pos(active.id(), "800", "58", "50", 5, "2026-06-03T03:00:00Z");
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                pos(active.id(), "1000", null, null, 0, "2026-06-01T03:00:00Z"),
                pos(active.id(), "500", "52", "50", 10, "2026-06-02T03:00:00Z"),
                latest));
        when(cyclePositionPort.findLatestByCycleIds(Set.of(active.id()))).thenReturn(Map.of(active.id(), latest));
        return active;
    }

    @Test
    void summary_activeCycleQuarterSell_realizesSoldPortionAndKeepsTotal() {
        stubUserWithStrategy();
        StrategyCycle active = stubActiveInfiniteQuarterSell();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));

        StatsSummary summary = statsService.getSummary(USER_ID, Currency.USD);

        // 총손익 = 800 + 5 × 58 − 1000 = 90, 실현 = 5 × (60 − 50) = 50, 미실현 = 90 − 50
        assertThat(summary.totalRealizedPnl()).isEqualByComparingTo("50.00");
        assertThat(summary.totalUnrealizedPnl()).isEqualByComparingTo("40.00");
        assertThat(summary.totalRealizedPnl().add(summary.totalUnrealizedPnl())).isEqualByComparingTo("90.00");
        StrategyTypeStats infinite = summary.byType().getFirst();
        assertThat(infinite.closedCycleCount()).isZero();
        assertThat(infinite.winRate()).isNull();
        assertThat(infinite.avgReturnRate()).isNull();
    }

    @Test
    void summary_activePrivacyMidStartPartialSell_realizesAtOpenPrice() {
        stubUserWith(PRIVACY_STRATEGY);
        // 개장: 예수금 100 + 10주(평단 50, 개장 종가 62) = 720 → 5주를 70에 매도, 최신 종가 66
        StrategyCycle active = cycleWithOpen(PRIVACY_STRATEGY_ID, "1500", "720", null);
        CyclePosition latest = pos(active.id(), "450", "66", "50", 5, "2026-02-01T03:00:00Z");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                pos(active.id(), "100", "62", "50", 10, "2026-01-01T03:00:00Z"), latest));
        when(cyclePositionPort.findLatestByCycleIds(Set.of(active.id()))).thenReturn(Map.of(active.id(), latest));

        StrategyTypeStats privacy = statsService.getSummary(USER_ID, Currency.USD).byType().getFirst();

        assertThat(privacy.realizedPnl()).isEqualByComparingTo("40.00"); // 5 × (70 − 62), 평단 기준 100이 아니다
        assertThat(privacy.unrealizedPnl()).isEqualByComparingTo("20.00");
    }

    @Test
    void summary_activeRealized_keepsWinRateAvgReturnAndCyclePerformanceDefinitions() {
        stubUserWithStrategy();
        StrategyCycle active = stubActiveInfiniteQuarterSell();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31"),
                closedCycle("1000.00", "950.00", "2026-02-01", "2026-02-11"),
                active));

        StrategyTypeStats infinite = statsService.getSummary(USER_ID, Currency.USD).byType().getFirst();
        CyclePerformance activePerf = statsService.getCyclePerformances(USER_ID, null, null, null, null, 0, 10)
                .items().getFirst();

        // 실현 = 종료 +100 −50 + 진행 중 매도분 50, 미실현 = 진행 중 총손익 90 − 50
        assertThat(infinite.realizedPnl()).isEqualByComparingTo("100.00");
        assertThat(infinite.unrealizedPnl()).isEqualByComparingTo("40.00");
        // 승률·평균 수익률은 종료 사이클 end − 원금 기준 그대로
        assertThat(infinite.winRate()).isEqualByComparingTo("0.5000");
        assertThat(infinite.avgReturnRate()).isEqualByComparingTo("0.0250");
        // 성과 목록의 진행 중 손익은 최신 평가 − 원금 그대로
        assertThat(activePerf.cycleId()).isEqualTo(active.id());
        assertThat(activePerf.pnl()).isEqualByComparingTo("90.00");
    }

    // ── CQ-70 끝난 전략 손익을 곡선에 유지 ───────────────────────────────

    @Test
    void equityCurve_endedStrategy_lastPointPnlEqualsSummaryTotal() {
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID)))
                .thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY, PRIVACY_STRATEGY)));
        // INFINITE: +1000 회전 뒤 +500으로 끝나고 후속 없음(시드 정책 NONE), PRIVACY: 진행 중 +20
        StrategyCycle c1 = cycleOf("10000", "11000", "2026-05-01", "2026-06-01");
        StrategyCycle c2 = cycleOf("10000", "10500", "2026-06-01", "2026-06-10");
        StrategyCycle b = activeCycle(PRIVACY_STRATEGY_ID, "500", "2026-06-05");
        CyclePosition bLatest = pos(b.id(), "300", "55", "50", 4, "2026-06-12T03:00:00Z");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(c1, c2, b));
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(List.of(
                depositSnapshot(c1.id(), "11000", "2026-06-01T01:00:00Z"),
                pos(b.id(), "500", null, null, 0, "2026-06-05T03:00:00Z"),
                depositSnapshot(c2.id(), "10500", "2026-06-10T01:00:00Z"),
                bLatest));
        when(cyclePositionPort.findLatestByCycleIds(Set.of(b.id()))).thenReturn(Map.of(b.id(), bLatest));

        EquityPoint last = statsService.getEquityCurve(USER_ID, Currency.USD, null, null, LocalDate.parse("2026-06-30")).points().getLast();
        StatsSummary summary = statsService.getSummary(USER_ID, Currency.USD);

        assertThat(last.date()).isEqualTo(LocalDate.parse("2026-06-12"));
        assertThat(last.totalAsset()).isEqualByComparingTo("520.00"); // 끝난 전략 자산은 빠진다
        BigDecimal summaryTotal = summary.totalRealizedPnl().add(summary.totalUnrealizedPnl());
        assertThat(summaryTotal).isEqualByComparingTo("1520.00");
        assertThat(last.totalAsset().subtract(last.principal())).isEqualByComparingTo(summaryTotal);
    }

    // ── KRW(UPBIT) 응답 금액 정수 반올림 ─────────────────────────────────────────

    // UPBIT 계좌·DCA 전략을 스텁하고 전략을 돌려준다 — 사이클 스텁은 호출 측이 한다
    private Strategy stubUpbitStrategy() {
        Account upbit = new Account(UUID.randomUUID(), USER_ID, "업비트", "n", "k", "s", null, Broker.UPBIT, null, null);
        Strategy s = new Strategy(UUID.randomUUID(), upbit.id(), StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(upbit));
        when(strategyPort.findByAccountIds(List.of(upbit.id()))).thenReturn(Map.of(upbit.id(), List.of(s)));
        return s;
    }

    private static StrategyCycle closedKrwCycle(UUID strategyId, String start, String end) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, new BigDecimal(start), new BigDecimal(end),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void krwSummary_roundsAmountsToScale0_ratiosUnchanged() {
        Strategy s = stubUpbitStrategy();
        StrategyCycle closed = closedKrwCycle(s.id(), "1000.40", "1100.75"); // 실현 100.35
        StrategyCycle open = activeCycle(s.id(), "1500.20", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(closed, open));
        when(cyclePositionPort.findLatestByCycleIds(Set.of(open.id())))
                .thenReturn(Map.of(open.id(), depositSnapshot(open.id(), "2000.60", "2026-06-02T01:00:00Z"))); // 미실현 500.40

        StatsSummary summary = statsService.getSummary(USER_ID, Currency.KRW);
        StrategyTypeStats dca = summary.byType().getFirst();

        assertThat(dca.realizedPnl()).hasToString("100");
        assertThat(dca.unrealizedPnl()).hasToString("501"); // 총손익 600.75 → 601 − 실현 100
        assertThat(summary.totalRealizedPnl()).hasToString("100");
        assertThat(summary.totalUnrealizedPnl()).hasToString("501");
        assertThat(summary.activePrincipal()).hasToString("1500");
        // 평균 수익률은 반올림 전 실현손익(100.35)/원금(1000.40)으로 계산된다
        assertThat(dca.avgReturnRate()).isEqualByComparingTo(
                new BigDecimal("100.35").divide(new BigDecimal("1000.40"), 6, java.math.RoundingMode.HALF_UP)
                        .setScale(4, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void krwSummary_negativeUnrealized_keepsSumInvariant() {
        Strategy s = stubUpbitStrategy();
        StrategyCycle open = activeCycle(s.id(), "1000.20", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(open));
        when(cyclePositionPort.findLatestByCycleIds(Set.of(open.id())))
                .thenReturn(Map.of(open.id(), depositSnapshot(open.id(), "999.45", "2026-06-02T01:00:00Z"))); // 평가손 -0.75

        StatsSummary summary = statsService.getSummary(USER_ID, Currency.KRW);
        StrategyTypeStats dca = summary.byType().getFirst();

        // 총손익 -0.75 → -1, 실현 0이라 미실현 -1
        assertThat(dca.realizedPnl().add(dca.unrealizedPnl())).hasToString("-1");
        assertThat(summary.totalRealizedPnl().add(summary.totalUnrealizedPnl())).hasToString("-1");
    }

    @Test
    void krwEquityCurve_pointsScale0() {
        Strategy s = stubUpbitStrategy();
        StrategyCycle open = activeCycle(s.id(), "1500.20", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(open));
        when(cyclePositionPort.findByCycleIdsAndRange(eq(Set.of(open.id())), any(), any())).thenReturn(List.of(
                depositSnapshot(open.id(), "2000.60", "2026-06-02T01:00:00Z")));

        EquityCurve c = statsService.getEquityCurve(USER_ID, Currency.KRW, null,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(c.points()).hasSize(1);
        assertThat(c.points().getFirst().totalAsset()).hasToString("2001");
        assertThat(c.points().getFirst().principal()).hasToString("1500");
    }

    @Test
    void krwCycles_amountsScale0_pnlIsDifferenceOfRounded_returnRateFromRaw() {
        Strategy s = stubUpbitStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(closedKrwCycle(s.id(), "1000.40", "1100.75")));

        CyclePerformance p = statsService.getCyclePerformances(USER_ID, null, null, null, null, 0, 10).items().getFirst();

        assertThat(p.startAmount()).hasToString("1000");
        assertThat(p.endAmount()).hasToString("1101");
        assertThat(p.pnl()).hasToString("101"); // 반올림된 1101 − 1000
        assertThat(p.returnRate()).isEqualByComparingTo(
                new BigDecimal("100.35").divide(new BigDecimal("1000.40"), 4, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void krwCycles_openWithoutSnapshot_keepsNullAmounts() {
        Strategy s = stubUpbitStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(activeCycle(s.id(), "1500.20", "2026-06-01")));

        CyclePerformance p = statsService.getCyclePerformances(USER_ID, null, null, null, null, 0, 10).items().getFirst();

        assertThat(p.endAmount()).isNull();
        assertThat(p.pnl()).isNull();
        assertThat(p.startAmount()).hasToString("1500");
    }

    @Test
    void usdSummary_keepsScale2() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(
                closedCycle("1000.00", "1100.00", "2026-01-01", "2026-01-31")));

        StatsSummary summary = statsService.getSummary(USER_ID, Currency.USD);

        assertThat(summary.totalRealizedPnl().scale()).isEqualTo(2);
        assertThat(summary.byType().getFirst().unrealizedPnl().scale()).isEqualTo(2);
    }
}
