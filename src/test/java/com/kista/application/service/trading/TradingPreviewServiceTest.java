package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.NextOrdersPreview.SkipReason;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.BrokerPricePort;
import com.kista.domain.strategy.*;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingPreviewServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort cyclePort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock BrokerAdapterRegistry registry;  // BrokerPricePort 조회 경유
    @Mock BrokerPricePort pricePort;       // registry.require(account, BrokerPricePort.class) 반환값
    @Mock PrivacyTradePort privacyTradePort;
    @Mock CyclePositionPort cycleHistoryPort;
    @Mock CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    @Mock StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    @Mock InfiniteTradingStrategy infiniteStrategy;
    @Mock PrivacyTradingStrategy privacyStrategy;
    @Mock OrderPort orderPort;
    @Mock NotifyPort notifyPort;

    TradingPreviewService service;

    static final BigDecimal PRICE = new BigDecimal("22.00");

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", null,
            Account.Broker.KIS, null
    );

    static final Strategy CYCLE = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE
    );
    static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();

    static final StrategyCycle STRATEGY_CYCLE = new StrategyCycle(
            UUID.randomUUID(), CYCLE.id(), STRATEGY_VERSION_ID, new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);

    static final UUID CYCLE_ID = STRATEGY_CYCLE.id(); // CyclePosition은 strategyCycleId 참조

    static final CyclePosition NORMAL_HISTORY = new CyclePosition(
            null, CYCLE_ID, new BigDecimal("1000.00"), new BigDecimal("22.00"), new BigDecimal("20.00"), 10, null, null);
    static final CyclePosition FRESH_HISTORY = new CyclePosition(
            null, CYCLE_ID, new BigDecimal("1000.00"), null, null, 0, null, null);

    @BeforeEach
    void setUp() {
        TradingBalanceLoader balanceLoader = new TradingBalanceLoader(cycleHistoryPort);
        ReverseInfiniteTradingStrategy reverseStrategy = mock(ReverseInfiniteTradingStrategy.class);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy),
                new PrivacyCycleOrderStrategy(privacyStrategy)));
        CycleOrderComputer orderComputer = new CycleOrderComputer(
                cycleStrategies, cycleHistoryPort, cyclePositionInfiniteDetailPort, strategyInfiniteDetailPort);
        // registry.require(account, BrokerPricePort.class) → pricePort 반환 스텁 (일부 테스트는 도달 전 종료 → lenient)
        lenient().doReturn(pricePort).when(registry).require(any(Account.class), any());
        service = new TradingPreviewService(accountPort, cyclePort, strategyCyclePort, orderPort, registry, privacyTradePort, balanceLoader, orderComputer, cycleStrategies);
        // 예외 경로 테스트에서는 이 stub이 호출되지 않으므로 lenient 처리
        lenient().when(orderPort.findPlannedByCycleAndDate(any(), any())).thenReturn(List.of());
        lenient().when(orderPort.sumPlannedBuyByAccountAndDate(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(cyclePositionInfiniteDetailPort.findLatestByCycleId(any(), anyInt())).thenReturn(List.of());
        lenient().when(strategyInfiniteDetailPort.findByStrategyVersionId(any())).thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 40)));
        lenient().when(strategyInfiniteDetailPort.findActiveByStrategyId(any())).thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 40)));
    }

    @Test
    void preview_returnsResult_whenHistoryExistsAndInfinite() {
        Order order = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, null, null, null);

        when(cyclePort.findByIdOrThrow(CYCLE.id())).thenReturn(CYCLE);
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        when(strategyCyclePort.findLatestByStrategyId(CYCLE.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        when(cycleHistoryPort.findLatestByStrategyId(CYCLE.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(pricePort.getPriceSnapshot(Ticker.SOXL, ACCOUNT))
                .thenReturn(new PriceSnapshot(PRICE, new BigDecimal("21.00")));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(order));

        NextOrdersPreview result = service.preview(CYCLE.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isNull();
        assertThat(result.position()).isNotNull();
        assertThat(result.position().ticker()).isEqualTo(Ticker.SOXL);
        assertThat(result.orders()).hasSize(1);
        // preview는 DB 저장 없음
        verify(orderPort, never()).saveAll(any());
    }

    @Test
    void preview_returnsSkipNoCycleHistory_whenNoHistory() {
        when(cyclePort.findByIdOrThrow(CYCLE.id())).thenReturn(CYCLE);
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        when(strategyCyclePort.findLatestByStrategyId(CYCLE.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        when(cycleHistoryPort.findLatestByStrategyId(CYCLE.id(), 1)).thenReturn(List.of());

        NextOrdersPreview result = service.preview(CYCLE.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_CYCLE_HISTORY);
        assertThat(result.position()).isNull();
        assertThat(result.orders()).isEmpty();
        verify(pricePort, never()).getPriceSnapshot(any(), any());
    }

    @Test
    void preview_returnsSkipNoPrivacyBase_whenPrivacyAndNoBase() {
        Strategy privacyCycle = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.PRIVACY,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);

        StrategyCycle privacyCycleCycle = new StrategyCycle(UUID.randomUUID(), privacyCycle.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);

        when(cyclePort.findByIdOrThrow(privacyCycle.id())).thenReturn(privacyCycle);
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        when(strategyCyclePort.findLatestByStrategyId(privacyCycle.id())).thenReturn(Optional.of(privacyCycleCycle));
        when(cycleHistoryPort.findLatestByStrategyId(privacyCycle.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.empty());

        NextOrdersPreview result = service.preview(privacyCycle.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_PRIVACY_BASE);
        assertThat(result.position()).isNull();
        assertThat(result.orders()).isEmpty();
    }

    @Test
    void preview_returnsOrders_whenPrivacyAndBaseExists() {
        Strategy privacyCycle = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.PRIVACY,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        PrivacyTradeBase base = new PrivacyTradeBase(
                UUID.randomUUID(), new BigDecimal("20.00"), 10, new BigDecimal("18.00"), List.of());
        Order buyOrder = new Order(null, ACCOUNT.id(), null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 5, new BigDecimal("19.00"),
                Order.OrderStatus.PLANNED, null, null, null);

        StrategyCycle privacyCycleCycle2 = new StrategyCycle(UUID.randomUUID(), privacyCycle.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);

        when(cyclePort.findByIdOrThrow(privacyCycle.id())).thenReturn(privacyCycle);
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        when(strategyCyclePort.findLatestByStrategyId(privacyCycle.id())).thenReturn(Optional.of(privacyCycleCycle2));
        when(cycleHistoryPort.findLatestByStrategyId(privacyCycle.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.of(base));
        when(privacyStrategy.buildOrders(any(), any(), any())).thenReturn(List.of(buyOrder));

        NextOrdersPreview result = service.preview(privacyCycle.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isNull();
        assertThat(result.position()).isNull(); // PRIVACY는 InfinitePosition 없음
        assertThat(result.orders()).hasSize(1);
        verify(orderPort, never()).saveAll(any()); // DB INSERT 없음
    }

    @Test
    void preview_throwsSecurityException_whenNotOwner() {
        UUID otherId = UUID.randomUUID();
        when(cyclePort.findByIdOrThrow(CYCLE.id())).thenReturn(CYCLE);
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);

        assertThatThrownBy(() -> service.preview(CYCLE.id(), otherId))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void preview_throwsNoSuchElementException_whenStrategyNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(cyclePort.findByIdOrThrow(unknownId))
                .thenThrow(new NoSuchElementException("전략 없음: " + unknownId));

        assertThatThrownBy(() -> service.preview(unknownId, ACCOUNT.userId()))
                .isInstanceOf(NoSuchElementException.class);
    }
}
