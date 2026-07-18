package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.NextOrdersPreview.SkipReason;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.strategy.CycleOrderStrategy;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingPreviewServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock OrderPort orderPort;
    @Mock StrategyOrderPlanBuilder planBuilder;
    @Mock TradingBuyCompetitionSimulator competitionSimulator;

    TradingPreviewService service;

    static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());

    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);

    static final StrategyCycle STRATEGY_CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);

    @BeforeEach
    void setUp() {
        service = new TradingPreviewService(accountPort, strategyPort, strategyCyclePort, orderPort, planBuilder, competitionSimulator);
        lenient().when(strategyPort.findByIdOrThrow(STRATEGY.id())).thenReturn(STRATEGY);
        lenient().when(accountPort.requireOwnedAccount(ACCOUNT.id(), ACCOUNT.userId())).thenReturn(ACCOUNT);
        lenient().when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        lenient().when(orderPort.findPlannedOrPlacedByCycleAndDate(any(), any())).thenReturn(List.of());
        lenient().when(orderPort.sumPlannedBuyByAccountAndDate(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void preview_returnsOrdersWithoutCompetition_whenPlanHasNoBuyOrders() {
        Order sellOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(sellOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isNull();
        assertThat(result.orders()).hasSize(1);
        assertThat(result.competition()).isNull();
        verify(competitionSimulator, never()).simulate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void preview_callsCompetitionSimulator_whenPlanHasBuyOrders() {
        Order buyOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 5, new BigDecimal("20.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(buyOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                true, new BigDecimal("1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, List.of(), List.of());
        when(competitionSimulator.simulate(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), eq(List.of(buyOrder)), any(), eq(BigDecimal.ZERO)))
                .thenReturn(competition);

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.competition()).isSameAs(competition);
    }

    @Test
    void preview_propagatesNonZeroOtherStrategiesPlannedBuyUsd_toSimulator() {
        // 이 전략의 사이클에 이미 존재하는 당일 PLANNED BUY (수량 5 @ 10.00 = 50.00)
        Order existingBuyOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 5, new BigDecimal("10.00"));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingBuyOrder));
        // 계좌 전체 당일 PLANNED BUY 합계 300.00 (타 전략분 포함)
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any()))
                .thenReturn(new BigDecimal("300.00"));

        Order newBuyOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 2, new BigDecimal("20.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(newBuyOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                true, new BigDecimal("1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, List.of(), List.of());
        // 300.00(계좌 전체) - 50.00(이 전략분) = 250.00(타 전략분)이 그대로 전파되는지 검증
        when(competitionSimulator.simulate(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), eq(List.of(newBuyOrder)), any(), eq(new BigDecimal("250.00"))))
                .thenReturn(competition);

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.competition()).isSameAs(competition);
        verify(competitionSimulator).simulate(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), eq(List.of(newBuyOrder)), any(), eq(new BigDecimal("250.00")));
    }

    @Test
    void preview_returnsSkip_whenPlanBuilderSkips() {
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(null, SkipReason.NO_CYCLE_HISTORY));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_CYCLE_HISTORY);
        assertThat(result.orders()).isEmpty();
        assertThat(result.competition()).isNull();
        verify(competitionSimulator, never()).simulate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void preview_throwsSecurityException_whenNotOwner() {
        UUID otherId = UUID.randomUUID();
        when(accountPort.requireOwnedAccount(ACCOUNT.id(), otherId)).thenThrow(new SecurityException());

        assertThatThrownBy(() -> service.preview(STRATEGY.id(), otherId))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void preview_throwsNoSuchElementException_whenStrategyNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(unknownId))
                .thenThrow(new NoSuchElementException("전략 없음: " + unknownId));

        assertThatThrownBy(() -> service.preview(unknownId, ACCOUNT.userId()))
                .isInstanceOf(NoSuchElementException.class);
    }
}
