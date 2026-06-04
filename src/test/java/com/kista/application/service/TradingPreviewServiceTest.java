package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.port.in.GetNextOrdersUseCase;
import com.kista.domain.port.in.GetNextOrdersUseCase.SkipReason;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.InfiniteTradingStrategy;
import com.kista.domain.strategy.PrivacyTradingStrategy;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingPreviewServiceTest {

    @Mock AccountPort accountPort;
    @Mock TradingCyclePort cyclePort;
    @Mock KisPricePort kisPricePort;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock TradingCycleHistoryPort cycleHistoryPort;
    @Mock InfiniteTradingStrategy infiniteStrategy;
    @Mock PrivacyTradingStrategy privacyStrategy;
    @Mock OrderPort orderPort;

    TradingPreviewService service;

    static final BigDecimal PRICE = new BigDecimal("22.00");

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", "01",
            Account.Broker.KIS
    );

    static final TradingCycle CYCLE = new TradingCycle(
            UUID.randomUUID(), ACCOUNT.id(), TradingCycle.Type.INFINITE,
            TradingCycle.Status.ACTIVE, Ticker.SOXL, null,
            TradingCycle.CycleSeedType.NONE
    );

    static final TradingCycleHistory NORMAL_HISTORY = new TradingCycleHistory(
            null, CYCLE.id(), new BigDecimal("1000.00"), new BigDecimal("22.00"), new BigDecimal("20.00"), 10, null);
    static final TradingCycleHistory FRESH_HISTORY = new TradingCycleHistory(
            null, CYCLE.id(), new BigDecimal("1000.00"), null, null, 0, null);
    static final TradingCycleHistory LOW_HISTORY = new TradingCycleHistory(
            null, CYCLE.id(), BigDecimal.ZERO, null, null, 0, null);

    @BeforeEach
    void setUp() {
        TradingBalanceLoader balanceLoader = new TradingBalanceLoader(cycleHistoryPort);
        TradingOrderPlanner orderPlanner = new TradingOrderPlanner(infiniteStrategy, privacyStrategy, orderPort);
        service = new TradingPreviewService(accountPort, cyclePort, kisPricePort, privacyTradePort, balanceLoader, orderPlanner);
    }

    @Test
    void preview_returnsResult_whenHistoryExistsAndInfinite() {
        Order order = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, null);

        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        when(cyclePort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(CYCLE));
        when(cycleHistoryPort.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(kisPricePort.getPrice(Ticker.SOXL, ACCOUNT)).thenReturn(PRICE);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(order));

        GetNextOrdersUseCase.Result result = service.preview(ACCOUNT.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isNull();
        assertThat(result.position()).isNotNull();
        assertThat(result.position().ticker()).isEqualTo(Ticker.SOXL);
        assertThat(result.position().currentPrice()).isEqualByComparingTo(PRICE);
        assertThat(result.orders()).hasSize(1);
        // preview는 DB 저장 없음
        verify(orderPort, never()).saveAll(any());
    }

    @Test
    void preview_returnsSkipNoCycleHistory_whenNoHistory() {
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        when(cyclePort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(CYCLE));
        when(cycleHistoryPort.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of());

        GetNextOrdersUseCase.Result result = service.preview(ACCOUNT.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_CYCLE_HISTORY);
        assertThat(result.position()).isNull();
        assertThat(result.orders()).isEmpty();
        verify(kisPricePort, never()).getPrice(any(), any());
    }

    @Test
    void preview_returnsSkipInsufficientBalance_whenShouldSkip() {
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        when(cyclePort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(CYCLE));
        when(cycleHistoryPort.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(LOW_HISTORY));

        GetNextOrdersUseCase.Result result = service.preview(ACCOUNT.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(SkipReason.INSUFFICIENT_BALANCE);
        assertThat(result.position()).isNull();
        assertThat(result.orders()).isEmpty();
        verify(kisPricePort, never()).getPrice(any(), any());
    }

    @Test
    void preview_returnsSkipNoPrivacyBase_whenPrivacyAndNoBase() {
        TradingCycle privacyCycle = new TradingCycle(
                UUID.randomUUID(), ACCOUNT.id(), TradingCycle.Type.PRIVACY,
                TradingCycle.Status.ACTIVE, Ticker.SOXL, new BigDecimal("1000.00"),
                TradingCycle.CycleSeedType.NONE);

        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        when(cyclePort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(privacyCycle));
        when(cycleHistoryPort.findRecentByCycleId(privacyCycle.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.empty());

        GetNextOrdersUseCase.Result result = service.preview(ACCOUNT.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_PRIVACY_BASE);
        assertThat(result.position()).isNull();
        assertThat(result.orders()).isEmpty();
    }

    @Test
    void preview_returnsOrders_whenPrivacyAndBaseExists() {
        TradingCycle privacyCycle = new TradingCycle(
                UUID.randomUUID(), ACCOUNT.id(), TradingCycle.Type.PRIVACY,
                TradingCycle.Status.ACTIVE, Ticker.SOXL, new BigDecimal("1000.00"),
                TradingCycle.CycleSeedType.NONE);
        PrivacyTradeBase base = new PrivacyTradeBase(
                UUID.randomUUID(), new BigDecimal("20.00"), 10, new BigDecimal("18.00"), List.of());
        Order buyOrder = new Order(null, ACCOUNT.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 5, new BigDecimal("19.00"),
                Order.OrderStatus.PLANNED, null);

        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        when(cyclePort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(privacyCycle));
        when(cycleHistoryPort.findRecentByCycleId(privacyCycle.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.of(base));
        when(privacyStrategy.buildOrders(any(), any(), any())).thenReturn(List.of(buyOrder));

        GetNextOrdersUseCase.Result result = service.preview(ACCOUNT.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isNull();
        assertThat(result.position()).isNull(); // PRIVACY는 InfinitePosition 없음
        assertThat(result.orders()).hasSize(1);
        verify(orderPort, never()).saveAll(any()); // DB INSERT 없음
    }

    @Test
    void preview_throwsSecurityException_whenNotOwner() {
        UUID otherId = UUID.randomUUID();
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);

        assertThatThrownBy(() -> service.preview(ACCOUNT.id(), otherId))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void preview_throwsNoSuchElementException_whenNoActiveCycle() {
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        when(cyclePort.findByAccountId(ACCOUNT.id())).thenReturn(List.of());

        assertThatThrownBy(() -> service.preview(ACCOUNT.id(), ACCOUNT.userId()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("활성 거래 사이클이 없습니다");
    }
}
