package com.kista.application.service.stats;

import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.stats.*;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.toss.TossApiException;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.ExchangeRatePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock HousingBenchmarkPricePort housingBenchmarkPricePort;
    @Mock ExchangeRatePort exchangeRatePort;
    @InjectMocks StatsService statsService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 2, 28);

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

    private static HousingBenchmarkPrice benchmarkPrice(
            LocalDate month, String first, String second, String third, String fourth, String fifth) {
        return new HousingBenchmarkPrice(
                HousingBenchmarkPrice.SOURCE_KBLAND,
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE,
                "1100000000", "서울", month,
                new BigDecimal(first), new BigDecimal(second), new BigDecimal(third),
                new BigDecimal(fourth), new BigDecimal(fifth), new BigDecimal("6.5"),
                LocalDate.of(2026, 2, 15), Instant.parse("2026-02-16T00:00:00Z"));
    }

    private static List<HousingBenchmarkPrice> benchmarkPrices() {
        return List.of(
                benchmarkPrice(FROM, "10", "20", "40", "80", "160"),
                benchmarkPrice(LocalDate.of(2026, 2, 1), "20", "60", "160", "400", "960"));
    }

    private StrategyCycle stubPortfolioComparison(String januaryValue, String februaryValue) {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-01T01:00:00Z"),
                depositSnapshot(cycle.id(), januaryValue, "2026-01-31T01:00:00Z"),
                depositSnapshot(cycle.id(), februaryValue, "2026-02-28T01:00:00Z")));
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                anyString(), anyString(), any(), any())).thenReturn(benchmarkPrices());
        return cycle;
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
    void equityCurve_조회_경계는_KST_자정() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of());
        when(cyclePositionPort.findByUserAndRange(any(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);

        statsService.getEquityCurve(USER_ID, null, LocalDate.of(2026, 7, 18));

        // to=2026-07-18 → toInstant = 2026-07-19T00:00 KST = 2026-07-18T15:00:00Z
        verify(cyclePositionPort).findByUserAndRange(eq(USER_ID), any(), toCaptor.capture());
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

    @Test
    void 포트폴리오와_서울_아파트를_첫_공통_월_100으로_비교한다() {
        stubPortfolioComparison("100.00", "184.20");
        when(exchangeRatePort.getExchangeRate()).thenReturn(
                new TossExchangeRate(new BigDecimal("1370.00"), new BigDecimal("1365.20")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, FROM, TO);

        assertThat(result.scope()).isEqualTo(BenchmarkScope.PORTFOLIO);
        assertThat(result.strategy()).isNull();
        assertThat(result.benchmark().regionCode()).isEqualTo("1100000000");
        assertThat(result.benchmark().regionName()).isEqualTo("서울");
        assertThat(result.benchmark().quintile()).isEqualTo(3);
        assertThat(result.points()).hasSize(2);
        assertThat(result.points().getFirst().investmentIndexUsd()).isEqualByComparingTo("100.0");
        assertThat(result.points().getFirst().benchmarkIndex()).isEqualByComparingTo("100.0");
        assertThat(result.points().getLast().investmentIndexUsd()).isEqualByComparingTo("184.2");
        assertThat(result.points().getLast().benchmarkIndex()).isEqualByComparingTo("400.0");
        assertThat(result.summary().investmentCumulativeReturn()).isEqualByComparingTo("0.842");
        assertThat(result.summary().benchmarkCumulativeReturn()).isEqualByComparingTo("3.0");
        assertThat(result.summary().excessReturn()).isEqualByComparingTo("-2.158");
        assertThat(result.summary().investmentAnnualizedReturn()).isCloseTo(
                BigDecimal.valueOf(Math.pow(1.842, 12.0) - 1.0),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.000000001")));
        assertThat(result.currentExchangeRate().midRate()).isEqualByComparingTo("1365.20");
        assertThat(result.currentExchangeRate().source()).isEqualTo("TOSS_INVEST");
        assertThat(result.currentExchangeRate().fetchedAt()).isNotNull();
        assertThat(result.emptyReason()).isNull();

        verify(housingBenchmarkPricePort).findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE,
                "1100000000", LocalDate.of(2025, 12, 1), LocalDate.of(2026, 2, 1));
        verify(exchangeRatePort, times(1)).getExchangeRate();
    }

    @Test
    void 같은_과거_월의_to는_월초와_월말_모두_같은_완료_월말로_정규화한다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-01T01:00:00Z"),
                depositSnapshot(cycle.id(), "100.00", "2026-01-31T01:00:00Z"),
                depositSnapshot(cycle.id(), "110.00", "2026-02-01T01:00:00Z"),
                depositSnapshot(cycle.id(), "120.00", "2026-02-28T01:00:00Z")));
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                anyString(), anyString(), any(), any())).thenReturn(benchmarkPrices());

        HousingBenchmarkComparison monthStart = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, FROM, LocalDate.of(2026, 2, 1));
        HousingBenchmarkComparison monthEnd = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, FROM, LocalDate.of(2026, 2, 28));

        assertThat(monthStart.points()).isEqualTo(monthEnd.points());
        assertThat(monthStart.points().getLast().investmentIndexUsd()).isEqualByComparingTo("120");
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(cyclePositionPort, times(2)).findByUserAndRange(
                eq(USER_ID), eq(Instant.EPOCH), toCaptor.capture());
        assertThat(toCaptor.getAllValues()).containsOnly(
                LocalDate.of(2026, 3, 1).atStartOfDay(TimeZones.KST).toInstant());
    }

    @Test
    void 현재_월은_미완료이므로_to를_생략해도_직전_완료_월까지만_비교한다() {
        YearMonth currentMonth = YearMonth.from(LocalDate.now(TimeZones.KST));
        YearMonth previousMonth = currentMonth.minusMonths(1);
        YearMonth twoMonthsAgo = currentMonth.minusMonths(2);
        LocalDate from = twoMonthsAgo.atDay(1);

        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", from.toString());
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", from.atStartOfDay(TimeZones.KST).toInstant().toString()),
                depositSnapshot(cycle.id(), "100.00", twoMonthsAgo.atEndOfMonth()
                        .atStartOfDay(TimeZones.KST).toInstant().toString()),
                depositSnapshot(cycle.id(), "120.00", previousMonth.atEndOfMonth()
                        .atStartOfDay(TimeZones.KST).toInstant().toString()),
                depositSnapshot(cycle.id(), "999.00", currentMonth.atDay(1)
                        .atStartOfDay(TimeZones.KST).toInstant().toString())));
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                benchmarkPrice(twoMonthsAgo.atDay(1), "100", "100", "100", "100", "100"),
                benchmarkPrice(previousMonth.atDay(1), "120", "120", "120", "120", "120"),
                benchmarkPrice(currentMonth.atDay(1), "999", "999", "999", "999", "999")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, from, null);

        assertThat(result.period().toMonth()).isEqualTo(previousMonth.atDay(1));
        assertThat(result.points().getLast().investmentIndexUsd()).isEqualByComparingTo("120");
        verify(cyclePositionPort).findByUserAndRange(
                USER_ID, Instant.EPOCH,
                currentMonth.atDay(1).atStartOfDay(TimeZones.KST).toInstant());
        verify(housingBenchmarkPricePort).findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE, "1100000000",
                twoMonthsAgo.minusMonths(1).atDay(1), previousMonth.atDay(1));
    }

    @Test
    void 아파트_분위는_각_가격_컬럼을_명시적으로_선택한다() {
        stubPortfolioComparison("100.00", "100.00");

        assertThat(List.of(1, 2, 3, 4, 5)).extracting(quintile ->
                        statsService.getHousingBenchmarkComparison(
                                        USER_ID, BenchmarkScope.PORTFOLIO, null, quintile, FROM, TO)
                                .points().getLast().benchmarkIndex())
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(
                        new BigDecimal("200"), new BigDecimal("300"), new BigDecimal("400"),
                        new BigDecimal("500"), new BigDecimal("600"));

        verify(exchangeRatePort, times(5)).getExchangeRate();
    }

    @Test
    void 투자와_아파트가_처음_함께_존재하는_월을_각각_100으로_재설정한다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-01T01:00:00Z"),
                depositSnapshot(cycle.id(), "100.00", "2026-01-31T01:00:00Z"),
                depositSnapshot(cycle.id(), "184.20", "2026-02-28T01:00:00Z"),
                depositSnapshot(cycle.id(), "200.00", "2026-03-31T01:00:00Z")));
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                benchmarkPrice(LocalDate.of(2026, 2, 1), "40", "40", "40", "40", "40"),
                benchmarkPrice(LocalDate.of(2026, 3, 1), "80", "80", "80", "80", "80")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, FROM, LocalDate.of(2026, 3, 31));

        assertThat(result.period().fromMonth()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(result.points().getFirst().investmentIndexUsd()).isEqualByComparingTo("100");
        assertThat(result.points().getFirst().benchmarkIndex()).isEqualByComparingTo("100");
        assertThat(result.points().getLast().investmentIndexUsd())
                .isEqualByComparingTo(new BigDecimal("200")
                        .divide(new BigDecimal("184.2"), 10, java.math.RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")));
        assertThat(result.points().getLast().benchmarkIndex()).isEqualByComparingTo("200");
    }

    @Test
    void 공통_포인트_사이에_달력_결측_월이_있으면_다음_월간_수익률은_null이다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-01T01:00:00Z"),
                depositSnapshot(cycle.id(), "100.00", "2026-01-31T01:00:00Z"),
                depositSnapshot(cycle.id(), "110.00", "2026-02-28T01:00:00Z"),
                depositSnapshot(cycle.id(), "121.00", "2026-03-31T01:00:00Z")));
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                benchmarkPrice(LocalDate.of(2026, 1, 1), "100", "100", "100", "100", "100"),
                benchmarkPrice(LocalDate.of(2026, 3, 1), "121", "121", "121", "121", "121")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1));

        assertThat(result.points()).extracting(HousingBenchmarkPoint::baseMonth)
                .containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1));
        assertThat(result.points().getLast().investmentMonthlyReturn()).isNull();
        assertThat(result.points().getLast().benchmarkMonthlyReturn()).isNull();
        assertThat(result.summary().investmentCumulativeReturn()).isEqualByComparingTo("0.21");
        assertThat(result.summary().benchmarkCumulativeReturn()).isEqualByComparingTo("0.21");
    }

    @Test
    void 월말_지수의_고점_대비_최대낙폭을_계산한다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-01T01:00:00Z"),
                depositSnapshot(cycle.id(), "100.00", "2026-01-31T01:00:00Z"),
                depositSnapshot(cycle.id(), "80.00", "2026-02-28T01:00:00Z"),
                depositSnapshot(cycle.id(), "120.00", "2026-03-31T01:00:00Z")));
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                benchmarkPrice(FROM, "100", "100", "100", "100", "100"),
                benchmarkPrice(LocalDate.of(2026, 2, 1), "90", "90", "90", "90", "90"),
                benchmarkPrice(LocalDate.of(2026, 3, 1), "135", "135", "135", "135", "135")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, FROM, LocalDate.of(2026, 3, 31));

        assertThat(result.summary().investmentMaxDrawdown()).isEqualByComparingTo("-0.2");
        assertThat(result.summary().benchmarkMaxDrawdown()).isEqualByComparingTo("-0.1");
    }

    @Test
    void 소유한_개별_전략만_조회하고_전략_메타데이터를_반환한다() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(testAccount());
        StrategyCycle cycle = activeCycle("100.00", "2026-01-01");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any()))
                .thenReturn(List.of(
                        depositSnapshot(cycle.id(), "100.00", "2026-01-01T01:00:00Z"),
                        depositSnapshot(cycle.id(), "110.00", "2026-02-28T01:00:00Z")));
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                anyString(), anyString(), any(), any())).thenReturn(benchmarkPrices());

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, STRATEGY_ID, 3, FROM, TO);

        assertThat(result.strategy().id()).isEqualTo(STRATEGY_ID);
        assertThat(result.strategy().type()).isEqualTo(Strategy.Type.INFINITE);
        assertThat(result.strategy().ticker()).isEqualTo(Strategy.Ticker.SOXL);
        verify(cyclePositionPort).findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any());
        verify(cyclePositionPort, never()).findByUserAndRange(any(), any(), any());
    }

    @Test
    void 소유하지_않은_전략은_포지션을_읽기_전에_거부한다() {
        UUID otherUserId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(new Account(
                ACCOUNT_ID, otherUserId, "타인계좌", "1", "key", "secret", null,
                Account.Broker.KIS, null));

        assertThatThrownBy(() -> statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, STRATEGY_ID, 3, FROM, TO))
                .isInstanceOf(SecurityException.class);

        verifyNoInteractions(cyclePositionPort, housingBenchmarkPricePort, exchangeRatePort);
    }

    @Test
    void 역전된_기간은_데이터를_읽기_전에_거부한다() {
        assertThatThrownBy(() -> statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(accountPort, strategyPort, strategyCyclePort,
                cyclePositionPort, housingBenchmarkPricePort, exchangeRatePort);
    }

    @Test
    void 투자_데이터가_없으면_NO_INVESTMENT_DATA를_반환한다() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of());
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of());
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                anyString(), anyString(), any(), any())).thenReturn(benchmarkPrices());

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, FROM, TO);

        assertThat(result.points()).isEmpty();
        assertThat(result.summary()).isNull();
        assertThat(result.emptyReason()).isEqualTo("NO_INVESTMENT_DATA");
        verify(exchangeRatePort).getExchangeRate();
    }

    @Test
    void 공통_월이_두_개_미만이면_INSUFFICIENT_COMMON_MONTHS를_반환한다() {
        stubPortfolioComparison("100.00", "110.00");
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(benchmarkPrices().getFirst()));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, FROM, TO);

        assertThat(result.points()).isEmpty();
        assertThat(result.summary()).isNull();
        assertThat(result.emptyReason()).isEqualTo("INSUFFICIENT_COMMON_MONTHS");
    }

    @Test
    void 환율_예외는_완성된_비교_결과에서_환율만_null로_격리한다() {
        stubPortfolioComparison("100.00", "184.20");
        when(exchangeRatePort.getExchangeRate())
                .thenReturn(new TossExchangeRate(new BigDecimal("1370.00"), new BigDecimal("1365.20")))
                .thenThrow(new TossApiException("환율 조회 실패", null));

        HousingBenchmarkComparison success = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, FROM, TO);
        HousingBenchmarkComparison isolated = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, FROM, TO);

        assertThat(isolated.currentExchangeRate()).isNull();
        assertThat(isolated.points()).isEqualTo(success.points());
        assertThat(isolated.period()).isEqualTo(success.period());
        assertThat(isolated.summary()).isEqualTo(success.summary());
        assertThat(isolated.benchmark()).isEqualTo(success.benchmark());
        assertThat(isolated.emptyReason()).isEqualTo(success.emptyReason());
        verify(exchangeRatePort, times(2)).getExchangeRate();
    }

    @Test
    void 시계열_조회는_from_to를_그대로_port에_전달한다() {
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE, "1100000000", FROM, TO))
                .thenReturn(benchmarkPrices());

        List<HousingBenchmarkPrice> result = statsService.getHousingBenchmarkSeries(FROM, TO);

        assertThat(result).isEqualTo(benchmarkPrices());
        verify(housingBenchmarkPricePort).findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE, "1100000000", FROM, TO);
    }

    @Test
    void 시계열_조회는_from_to가_모두_없으면_최소날짜부터_오늘까지_조회한다() {
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);

        statsService.getHousingBenchmarkSeries(null, null);

        verify(housingBenchmarkPricePort).findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                eq(HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE), eq("1100000000"),
                fromCaptor.capture(), toCaptor.capture());
        assertThat(fromCaptor.getValue()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(toCaptor.getValue()).isEqualTo(LocalDate.now(TimeZones.KST));
    }

    @Test
    void 시계열_조회는_역전된_기간을_거부한다() {
        assertThatThrownBy(() -> statsService.getHousingBenchmarkSeries(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(housingBenchmarkPricePort);
    }

    @Test
    void 누락되거나_0_이하인_현재_환율은_null로_격리한다() {
        stubPortfolioComparison("100.00", "184.20");
        when(exchangeRatePort.getExchangeRate()).thenReturn(
                null,
                new TossExchangeRate(new BigDecimal("1370.00"), null),
                new TossExchangeRate(BigDecimal.ZERO, BigDecimal.ZERO),
                new TossExchangeRate(new BigDecimal("-1"), new BigDecimal("-1")));

        for (int invocation = 0; invocation < 4; invocation++) {
            HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                    USER_ID, BenchmarkScope.PORTFOLIO, null, 3, FROM, TO);
            assertThat(result.currentExchangeRate()).isNull();
            assertThat(result.points()).isNotEmpty();
        }

        verify(exchangeRatePort, times(4)).getExchangeRate();
    }
}
