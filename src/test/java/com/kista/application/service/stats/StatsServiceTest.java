package com.kista.application.service.stats;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.stats.*;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @InjectMocks StatsService statsService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();

    private static final Strategy STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, Strategy.Type.INFINITE, Strategy.Status.ACTIVE,
            Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);

    // Account는 record(final) — mock(Account.class) 대신 실제 인스턴스 생성 (AccountServiceTest 패턴)
    private static Account testAccount() {
        return new Account(ACCOUNT_ID, USER_ID, "테스트계좌",
                "74420614", "appKey", "appSecret", null,
                Account.Broker.KIS, null);
    }

    private void stubUserWithStrategy() {
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount()));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(STRATEGY));
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
        assertThat(infinite.type()).isEqualTo(Strategy.Type.INFINITE);
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
        when(cyclePositionPort.findLatestOne(active.id())).thenReturn(Optional.of(
                new CyclePosition(UUID.randomUUID(), active.id(), new BigDecimal("500.00"),
                        new BigDecimal("55.00"), new BigDecimal("50.00"), 10, Instant.now(), null)));

        StatsSummary summary = statsService.getSummary(USER_ID);

        assertThat(summary.totalUnrealizedPnl()).isEqualByComparingTo("50.00");
        assertThat(summary.activePrincipal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void equity_curve는_같은_날_같은_사이클의_최신_스냅샷만_합산한다() {
        stubUserWithStrategy();
        StrategyCycle active = new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, null,
                new BigDecimal("1000.00"), null, LocalDate.parse("2026-06-01"), null,
                Instant.parse("2026-06-01T00:00:00Z"), null);
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(active));
        // KST 2026-06-02 (UTC 06-01 20:00 / 06-01 20:30) 스냅샷 2건 — 최신 건만 반영
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), any(), any())).thenReturn(List.of(
                new CyclePosition(UUID.randomUUID(), active.id(), new BigDecimal("900.00"),
                        new BigDecimal("10.00"), null, 5, Instant.parse("2026-06-01T20:00:00Z"), null),
                new CyclePosition(UUID.randomUUID(), active.id(), new BigDecimal("800.00"),
                        new BigDecimal("10.00"), null, 20, Instant.parse("2026-06-01T20:30:00Z"), null)));
        EquityCurve curve = statsService.getEquityCurve(
                USER_ID, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(1);
        assertThat(curve.points().get(0).date()).isEqualTo(LocalDate.parse("2026-06-02"));
        // 800 + 20 × 10.00 = 1000
        assertThat(curve.points().get(0).totalAsset()).isEqualByComparingTo("1000.00");
        assertThat(curve.points().get(0).principal()).isEqualByComparingTo("1000.00");
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
    void equity_curve는_스냅샷이_없는_날_직전_스냅샷을_carry_forward한다() {
        stubUserWithStrategy();
        StrategyCycle a = activeCycle("1000.00", "2026-06-01");
        StrategyCycle b = activeCycle("2000.00", "2026-06-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(a, b));
        // A는 KST 06-01·06-02 스냅샷, B는 06-01만 → 06-02 포인트에 B의 06-01 값이 carry-forward
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), any(), any())).thenReturn(List.of(
                depositSnapshot(a.id(), "1000.00", "2026-06-01T01:00:00Z"),
                depositSnapshot(b.id(), "2000.00", "2026-06-01T02:00:00Z"),
                depositSnapshot(a.id(), "1100.00", "2026-06-02T01:00:00Z")));
        EquityCurve curve = statsService.getEquityCurve(
                USER_ID, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

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
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), any(), any())).thenReturn(List.of(
                depositSnapshot(ended.id(), "1200.00", "2026-06-01T01:00:00Z"),
                depositSnapshot(active.id(), "500.00", "2026-06-01T02:00:00Z"),
                depositSnapshot(active.id(), "550.00", "2026-06-02T01:00:00Z")));
        EquityCurve curve = statsService.getEquityCurve(
                USER_ID, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(curve.points()).hasSize(2);
        // 06-01: 종료 사이클 포함 (endDate 당일까지 유효)
        assertThat(curve.points().get(0).totalAsset()).isEqualByComparingTo("1700.00");
        assertThat(curve.points().get(0).principal()).isEqualByComparingTo("1500.00");
        // 06-02: endDate(06-01) 경과 → 종료 사이클 제외
        assertThat(curve.points().get(1).totalAsset()).isEqualByComparingTo("550.00");
        assertThat(curve.points().get(1).principal()).isEqualByComparingTo("500.00");
    }
}
