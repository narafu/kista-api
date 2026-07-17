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
    @Mock IndexPricePort indexPricePort;
    @Mock IndexPriceFeedPort indexPriceFeedPort;
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
        when(indexPricePort.findMaxTradeDate("SPY")).thenReturn(Optional.of(LocalDate.parse("2026-07-16")));
        when(indexPricePort.findBySymbolAndRange(eq("SPY"), any(), any())).thenReturn(List.of());

        EquityCurve curve = statsService.getEquityCurve(
                USER_ID, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"), "SPY");

        assertThat(curve.points()).hasSize(1);
        assertThat(curve.points().get(0).date()).isEqualTo(LocalDate.parse("2026-06-02"));
        // 800 + 20 × 10.00 = 1000
        assertThat(curve.points().get(0).totalAsset()).isEqualByComparingTo("1000.00");
        assertThat(curve.points().get(0).principal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void 벤치마크_결손_구간은_피드에서_backfill하고_피드_실패면_저장분만_반환한다() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of());
        when(cyclePositionPort.findByUserAndRange(any(), any(), any())).thenReturn(List.of());
        when(indexPricePort.findMaxTradeDate("QQQ")).thenReturn(Optional.empty());
        when(indexPriceFeedPort.fetchDailyCloses(eq("QQQ"), any(), any()))
                .thenThrow(new RuntimeException("alpaca down"));
        when(indexPricePort.findBySymbolAndRange(eq("QQQ"), any(), any())).thenReturn(List.of());

        EquityCurve curve = statsService.getEquityCurve(
                USER_ID, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"), "QQQ");

        assertThat(curve.benchmark()).isEmpty(); // 실패해도 예외 전파 없이 정상 응답
    }

    @Test
    void 지원하지_않는_벤치마크_심볼은_거부한다() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> statsService.getEquityCurve(USER_ID, null, null, "TSLA"));
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
}
