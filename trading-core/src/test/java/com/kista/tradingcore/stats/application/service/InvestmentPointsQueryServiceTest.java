package com.kista.tradingcore.stats.application.service;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy;
import org.junit.jupiter.api.BeforeEach;
import java.util.TreeMap;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.stats.application.usecase.InvestmentPointsQuery;
import com.kista.tradingcore.stats.domain.model.InvestmentPointsResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
    @Mock OrderPort orderPort;
    @Mock CycleOrderStrategies cycleOrderStrategies;
    @InjectMocks InvestmentPointsQueryService service;

    @BeforeEach
    void stubSeedCapability() {
        CycleOrderStrategy seeded = org.mockito.Mockito.mock(CycleOrderStrategy.class);
        CycleOrderStrategy seedless = org.mockito.Mockito.mock(CycleOrderStrategy.class);
        org.mockito.Mockito.lenient().when(seeded.occupiesSeed()).thenReturn(true);
        org.mockito.Mockito.lenient().when(seedless.occupiesSeed()).thenReturn(false);
        org.mockito.Mockito.lenient().when(cycleOrderStrategies.of(any())).thenReturn(seeded);
        org.mockito.Mockito.lenient().when(cycleOrderStrategies.of(StrategyType.DCA)).thenReturn(seedless);
    }

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();

    private static final Strategy STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, StrategyType.INFINITE, StrategyStatus.ACTIVE,
            StrategyTicker.SOXL, StrategyCycleSeedType.NONE);

    private static Account testAccount(UUID ownerId) {
        return new Account(ACCOUNT_ID, ownerId, "테스트계좌",
                "74420614", "appKey", "appSecret", null, Broker.KIS, null);
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
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(testAccount(USER_ID));
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        when(strategyCyclePort.findByStrategyIds(Set.of(STRATEGY_ID))).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any()))
                .thenReturn(List.of(
                        depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                        depositSnapshot(cycle.id(), "110.00", "2026-02-23T01:00:00Z")));

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.STRATEGY,
                STRATEGY_ID, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.selectedStrategy()).isEqualTo(STRATEGY);
        assertThat(result.points()).isNotEmpty();
        verify(cyclePositionPort).findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any());
        verify(cyclePositionPort, never()).findByUserAndRange(any(), any(), any());
    }

    @Test
    void 소유하지_않은_전략은_사이클_포지션_조회_전에_거부한다() {
        // 옛 api BenchmarkServiceTest의 "소유하지 않은 전략은 포지션을 읽기 전에 거부한다" 불변식이
        // 소유권 검증 로직과 함께 trading 쪽으로 이전됐다 — fast-fail이 실제로 strategyCyclePort/
        // cyclePositionPort 호출보다 먼저 일어나는지는 이제 여기서만 검증된다.
        UUID otherUserId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(testAccount(otherUserId));

        assertThatThrownBy(() -> service.fetch(USER_ID, InvestmentPointsQuery.Scope.STRATEGY,
                STRATEGY_ID, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23)))
                .isInstanceOf(SecurityException.class);

        verify(strategyCyclePort, never()).findByStrategyIds(any());
        verify(cyclePositionPort, never()).findByStrategyAndRange(any(), any(), any());
        verify(cyclePositionPort, never()).findByUserAndRange(any(), any(), any());
    }

    @Test
    void PORTFOLIO_scope는_모의계좌를_제외한_전략만_조회한다() {
        UUID mockAccountId = UUID.randomUUID();
        Account mockAccount = new Account(mockAccountId, USER_ID, "모의계좌",
                "00000000", "key", "secret", null, Broker.MOCK, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(USER_ID), mockAccount));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY)));
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        ArgumentCaptor<Set<UUID>> strategyIdsCaptor = ArgumentCaptor.forClass(Set.class);
        when(strategyCyclePort.findByStrategyIds(strategyIdsCaptor.capture())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), "184.20", "2026-02-23T01:00:00Z")));

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.PORTFOLIO,
                null, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.selectedStrategy()).isNull();
        assertThat(result.points().getFirst().investmentIndexUsd()).isEqualByComparingTo("100.0");
        assertThat(strategyIdsCaptor.getValue()).containsExactly(STRATEGY_ID);
        verify(strategyPort, never()).findByAccountIds(argThat(ids -> ids.contains(mockAccountId)));
    }

    @Test
    void PORTFOLIO_scope는_DCA_전략도_포함하고_순투입액을_계산기에_넘긴다() {
        Strategy dca = new Strategy(UUID.randomUUID(), ACCOUNT_ID, StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(USER_ID)));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY, dca)));
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), dca.id(), null, BigDecimal.ZERO, null,
                LocalDate.of(2026, 1, 5), null, Instant.parse("2026-01-05T00:00:00Z"), null);
        ArgumentCaptor<Set<UUID>> idsCaptor = ArgumentCaptor.forClass(Set.class);
        when(strategyCyclePort.findByStrategyIds(idsCaptor.capture())).thenReturn(List.of(cycle));
        when(orderPort.findFilledNetInvestedByCycleIds(Set.of(cycle.id()))).thenReturn(Map.of(cycle.id(),
                new TreeMap<>(Map.of(LocalDate.of(2026, 1, 5), new BigDecimal("100"),
                        LocalDate.of(2026, 1, 6), new BigDecimal("200")))));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), "200.00", "2026-01-06T01:00:00Z")));

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.PORTFOLIO, null,
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6));

        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(STRATEGY_ID, dca.id());
        // 추가 매수 100은 외부 투입이라 지수는 100 유지
        assertThat(result.points().getLast().investmentIndexUsd()).isEqualByComparingTo("100");
    }

    @Test
    void PORTFOLIO_scope는_크립토_KRW_계좌를_제외한다() {
        UUID cryptoAccountId = UUID.randomUUID();
        Account crypto = new Account(cryptoAccountId, USER_ID, "업비트", "n", "k", "s", null, Broker.UPBIT, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(USER_ID), crypto));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY)));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of());

        service.fetch(USER_ID, InvestmentPointsQuery.Scope.PORTFOLIO, null,
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        verify(strategyPort, never()).findByAccountIds(argThat(ids -> ids.contains(cryptoAccountId)));
    }

    @Test
    void from이_없으면_사이클_시작일_중_최소값을_effectiveFrom으로_쓴다() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(testAccount(USER_ID));
        StrategyCycle cycle = activeCycle("100.00", "2026-01-15");
        when(strategyCyclePort.findByStrategyIds(Set.of(STRATEGY_ID))).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any()))
                .thenReturn(List.of(depositSnapshot(cycle.id(), "100.00", "2026-01-15T01:00:00Z")));

        InvestmentPointsResult result = service.fetch(USER_ID, InvestmentPointsQuery.Scope.STRATEGY,
                STRATEGY_ID, null, LocalDate.of(2026, 2, 23));

        assertThat(result.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(result.points().getFirst().baseDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }
}
