package com.kista.tradingcore.stats.application.service;

import com.kista.shared.support.FixedClockTestConfig;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.application.port.output.CycleCapitalEventPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import org.junit.jupiter.api.BeforeEach;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.stats.application.usecase.InvestmentPointsQuery;
import com.kista.tradingcore.stats.domain.model.InvestmentPointsResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Task 5로 api BenchmarkService.buildInvestmentContext에서 이전된 소유권 검증·MOCK 계좌 필터링·
// InvestmentIndexCalculator 배선 책임을 검증한다 — 스냅/벤치마크 비교 로직 자체는 api의
// BenchmarkServiceTest가 계속 커버한다(이 서비스는 순수 InvestmentPoint 시리즈만 반환).
@ExtendWith(MockitoExtension.class)
class InvestmentPointsQueryServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock CycleCapitalEventPort cycleCapitalEventPort;
    InvestmentPointsQueryService service;

    @BeforeEach
    void setUp() {
        service = new InvestmentPointsQueryService(accountPort, strategyPort, strategyCyclePort, cyclePositionPort, cycleCapitalEventPort, FixedClockTestConfig.FIXED);
    }

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();

    private static final Strategy STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, StrategyType.INFINITE, StrategyStatus.ACTIVE,
            StrategyTicker.SOXL, StrategyCycleSeedType.NONE);

    private static Account testAccount(UUID ownerId) {
        return new Account(ACCOUNT_ID, ownerId, "테스트계좌",
                "74420614", "appKey", "appSecret", null, Broker.KIS, null, null);
    }

    private static StrategyCycle activeCycle(String start, String startDate) {
        return new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, null,
                new BigDecimal(start), null, LocalDate.parse(startDate), null,
                Instant.parse(startDate + "T00:00:00Z"), null);
    }

    private static CyclePosition depositSnapshot(UUID cycleId, String deposit, String createdAt) {
        return new CyclePosition(UUID.randomUUID(), cycleId, new BigDecimal(deposit),
                null, null, BigDecimal.valueOf(0), Instant.parse(createdAt), null);
    }

    @Test
    void STRATEGY_scope는_소유한_전략만_조회한다() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(testAccount(USER_ID));
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        when(strategyCyclePort.findByStrategyIds(Set.of(STRATEGY_ID))).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any()))
                .thenReturn(List.of(
                        depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                        depositSnapshot(cycle.id(), "110.00", "2026-02-23T01:00:00Z")));

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.STRATEGY,
                STRATEGY_ID, Currency.USD, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.selectedStrategy()).isEqualTo(STRATEGY);
        assertThat(result.currency()).isEqualTo(Currency.USD);
        assertThat(result.points()).isNotEmpty();
        verify(cyclePositionPort).findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any());
        verify(cyclePositionPort, never()).findByUserAndRange(any(), any(), any());
    }

    @Test
    void STRATEGY_scope는_전략_계좌_통화를_싣는다() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        Account binance = new Account(ACCOUNT_ID, USER_ID, "바이낸스", "n", "k", "s", null, Broker.BINANCE, null, null);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(binance);
        when(strategyCyclePort.findByStrategyIds(Set.of(STRATEGY_ID))).thenReturn(List.of());
        when(cyclePositionPort.findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any())).thenReturn(List.of());

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.STRATEGY,
                STRATEGY_ID, Currency.USD, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.currency()).isEqualTo(Currency.USDT);
    }

    @Test
    void 소유하지_않은_전략은_사이클_포지션_조회_전에_거부한다() {
        // 옛 api BenchmarkServiceTest의 "소유하지 않은 전략은 포지션을 읽기 전에 거부한다" 불변식이
        // 소유권 검증 로직과 함께 trading 쪽으로 이전됐다 — fast-fail이 실제로 strategyCyclePort/
        // cyclePositionPort 호출보다 먼저 일어나는지는 이제 여기서만 검증된다.
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenThrow(new SecurityException("계좌에 대한 접근 권한이 없습니다"));

        assertThatThrownBy(() -> service.fetch(USER_ID, InvestmentPointsQuery.Scope.STRATEGY,
                STRATEGY_ID, Currency.USD, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23)))
                .isInstanceOf(SecurityException.class);

        verify(strategyCyclePort, never()).findByStrategyIds(any());
        verify(cyclePositionPort, never()).findByStrategyAndRange(any(), any(), any());
        verify(cyclePositionPort, never()).findByUserAndRange(any(), any(), any());
    }

    @Test
    void PORTFOLIO_scope는_모의계좌를_제외한_전략만_조회한다() {
        UUID mockAccountId = UUID.randomUUID();
        Account mockAccount = new Account(mockAccountId, USER_ID, "모의계좌",
                "00000000", "key", "secret", null, Broker.MOCK, null, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(USER_ID), mockAccount));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY)));
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        ArgumentCaptor<Set<UUID>> strategyIdsCaptor = ArgumentCaptor.forClass(Set.class);
        when(strategyCyclePort.findByStrategyIds(strategyIdsCaptor.capture())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), "184.20", "2026-02-23T01:00:00Z")));

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.PORTFOLIO,
                null, Currency.USD, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.selectedStrategy()).isNull();
        assertThat(result.currency()).isEqualTo(Currency.USD);
        assertThat(result.points().getFirst().investmentIndex()).isEqualByComparingTo("100.0");
        assertThat(strategyIdsCaptor.getValue()).containsExactly(STRATEGY_ID);
        verify(strategyPort, never()).findByAccountIds(argThat(ids -> ids.contains(mockAccountId)));
    }

    @Test
    void PORTFOLIO_scope는_DCA_전략도_포함한다() {
        Strategy dca = new Strategy(UUID.randomUUID(), ACCOUNT_ID, StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(USER_ID)));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY, dca)));
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), dca.id(), null, BigDecimal.ZERO, null,
                LocalDate.of(2026, 1, 5), null, Instant.parse("2026-01-05T00:00:00Z"), null);
        ArgumentCaptor<Set<UUID>> idsCaptor = ArgumentCaptor.forClass(Set.class);
        when(strategyCyclePort.findByStrategyIds(idsCaptor.capture())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), "200.00", "2026-01-06T01:00:00Z")));

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.PORTFOLIO, null, Currency.USD,
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6));

        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(STRATEGY_ID, dca.id());
        assertThat(result.points()).isNotEmpty();
    }

    private Account account(UUID id, Broker broker) {
        return new Account(id, USER_ID, "계좌", "n", "k", "s", null, broker, null, null);
    }

    @Test
    void PORTFOLIO_scope는_요청_통화_계좌_전략만_조회한다() {
        UUID binanceId = UUID.randomUUID();
        Strategy binanceStrategy = new Strategy(UUID.randomUUID(), binanceId, StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(USER_ID), account(binanceId, Broker.BINANCE)));
        when(strategyPort.findByAccountIds(List.of(binanceId))).thenReturn(Map.of(binanceId, List.of(binanceStrategy)));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of());

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.PORTFOLIO, null,
                Currency.USDT, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.currency()).isEqualTo(Currency.USDT);
        verify(strategyPort).findByAccountIds(List.of(binanceId));
    }

    @Test
    void PORTFOLIO_USDT는_사용자_전체_포지션을_읽어도_USD_포지션이_섞이지_않는다() {
        UUID binanceId = UUID.randomUUID();
        Strategy usdtStrategy = new Strategy(UUID.randomUUID(), binanceId, StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(USER_ID), account(binanceId, Broker.BINANCE)));
        when(strategyPort.findByAccountIds(List.of(binanceId))).thenReturn(Map.of(binanceId, List.of(usdtStrategy)));
        StrategyCycle usdtCycle = new StrategyCycle(UUID.randomUUID(), usdtStrategy.id(), null, BigDecimal.ZERO, null,
                LocalDate.of(2026, 1, 5), null, Instant.parse("2026-01-05T00:00:00Z"), null);
        StrategyCycle usdCycle = activeCycle("100.00", "2026-01-05");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(usdtCycle));
        List<CyclePosition> usdt = List.of(
                depositSnapshot(usdtCycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(usdtCycle.id(), "110.00", "2026-01-06T01:00:00Z"));
        List<CyclePosition> usd = List.of(
                depositSnapshot(usdCycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(usdCycle.id(), "300.00", "2026-01-06T01:00:00Z"));
        // 사용자 전체 포지션(USD+USDT)을 돌려줘도 사이클 조인으로 USDT 사이클만 반영돼야 한다
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any()))
                .thenReturn(java.util.stream.Stream.concat(usdt.stream(), usd.stream()).toList());

        InvestmentPointsResult mixed = service.fetch(USER_ID, InvestmentPointsQuery.Scope.PORTFOLIO, null,
                Currency.USDT, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(usdt);
        InvestmentPointsResult only = service.fetch(USER_ID, InvestmentPointsQuery.Scope.PORTFOLIO, null,
                Currency.USDT, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6));

        assertThat(mixed.points()).isEqualTo(only.points());
    }

    @Test
    void STRATEGY_scope는_currency_파라미터를_무시하고_전략_계좌_통화를_쓴다() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account(ACCOUNT_ID, Broker.BINANCE));
        when(strategyCyclePort.findByStrategyIds(Set.of(STRATEGY_ID))).thenReturn(List.of());
        when(cyclePositionPort.findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any())).thenReturn(List.of());

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.STRATEGY,
                STRATEGY_ID, Currency.USD, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.currency()).isEqualTo(Currency.USDT);
    }

    @Test
    void PORTFOLIO_계좌_없는_통화는_빈_points를_반환한다() {
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(USER_ID)));

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.PORTFOLIO, null,
                Currency.JPY, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.points()).isEmpty();
        assertThat(result.currency()).isEqualTo(Currency.JPY);
    }

    @Test
    void from이_없으면_사이클_시작일_중_최소값을_effectiveFrom으로_쓴다() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(testAccount(USER_ID));
        StrategyCycle cycle = activeCycle("100.00", "2026-01-15");
        when(strategyCyclePort.findByStrategyIds(Set.of(STRATEGY_ID))).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any()))
                .thenReturn(List.of(depositSnapshot(cycle.id(), "100.00", "2026-01-15T01:00:00Z")));

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.STRATEGY,
                STRATEGY_ID, Currency.USD, null, LocalDate.of(2026, 2, 23));

        assertThat(result.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(result.points().getFirst().baseDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void 투자금_추가와_인출이_있는_날의_수익률은_0이다() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(testAccount(USER_ID));
        StrategyCycle cycle = activeCycle("0", "2026-10-30");
        when(strategyCyclePort.findByStrategyIds(Set.of(STRATEGY_ID))).thenReturn(List.of(cycle));
        // 현재값(제자리 갱신된 행) — 11-07 행에 +100/-350 이력이 걸려 있다
        CyclePosition nov6 = new CyclePosition(UUID.randomUUID(), cycle.id(), new BigDecimal("700.00"),
                new BigDecimal("2238.59"), new BigDecimal("2268.34"), new BigDecimal("0.26291013"),
                Instant.parse("2026-11-06T00:10:02.630968Z"), null);
        CyclePosition nov7 = new CyclePosition(UUID.randomUUID(), cycle.id(), new BigDecimal("350.40"),
                new BigDecimal("2238.67"), new BigDecimal("2264.05"), new BigDecimal("0.30736563"),
                Instant.parse("2026-11-07T00:10:03.127918Z"), null);
        when(cyclePositionPort.findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any()))
                .thenReturn(List.of(nov6, nov7));
        when(cycleCapitalEventPort.findByCycleIds(any())).thenReturn(List.of(
                new CycleCapitalEvent(UUID.randomUUID(), cycle.id(), nov7.id(), CycleCapitalEvent.Kind.INJECT,
                        new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                        Instant.parse("2026-11-07T02:46:37.851115Z")),
                new CycleCapitalEvent(UUID.randomUUID(), cycle.id(), nov7.id(), CycleCapitalEvent.Kind.WITHDRAW,
                        new BigDecimal("-350"), BigDecimal.ZERO, new BigDecimal("-350"),
                        Instant.parse("2026-11-07T02:47:14.286694Z"))));

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.STRATEGY,
                STRATEGY_ID, Currency.USD, LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 7));

        // 수정 전에는 11-07 수익률이 약 -19.4%(1,288.55 -> 1,038.49)로 보였다
        assertThat(result.points().getLast().periodReturn().abs()).isLessThan(new BigDecimal("0.0001"));
    }

    @Test
    void 이력이_이후_날짜에_발생해도_걸린_행의_과거_평가는_원본으로_복원한다() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(testAccount(USER_ID));
        StrategyCycle cycle = activeCycle("0", "2026-11-05");
        when(strategyCyclePort.findByStrategyIds(Set.of(STRATEGY_ID))).thenReturn(List.of(cycle));
        CyclePosition nov5 = depositSnapshot(cycle.id(), "1000.00", "2026-11-05T01:00:00Z");
        CyclePosition nov6 = depositSnapshot(cycle.id(), "1100.00", "2026-11-06T01:00:00Z"); // 11-08 추가 후 현재값
        when(cyclePositionPort.findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any()))
                .thenReturn(List.of(nov5, nov6));
        when(cycleCapitalEventPort.findByCycleIds(any())).thenReturn(List.of(
                new CycleCapitalEvent(UUID.randomUUID(), cycle.id(), nov6.id(), CycleCapitalEvent.Kind.INJECT,
                        new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                        Instant.parse("2026-11-08T02:00:00Z"))));

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.STRATEGY,
                STRATEGY_ID, Currency.USD, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 8));

        // 복원 없이는 11-06에 +10%, 11-08에 -9%로 보인다
        assertThat(result.points()).allSatisfy(point ->
                assertThat(point.periodReturn().abs()).isLessThan(new BigDecimal("0.0001")));
    }
}
