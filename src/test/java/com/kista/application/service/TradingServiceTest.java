package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.order.*;
import com.kista.domain.model.kis.*;
import com.kista.domain.model.user.*;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CorrectionStrategy;
import com.kista.domain.strategy.TradingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingServiceTest {

    @Mock KisHolidayPort kisHolidayPort;
    @Mock KisAccountPort kisAccountPort;
    @Mock KisPricePort kisPricePort;
    @Mock KisOrderPort kisOrderPort;
    @Mock KisExecutionPort kisExecutionPort;
    @Mock TradingStrategy tradingStrategy;
    @Mock CorrectionStrategy correctionStrategy;
    @Mock TradeHistoryPort tradeHistoryPort;
    @Mock PortfolioSnapshotPort portfolioSnapshotPort;
    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort;
    @Mock OrderPort orderPort;
    @Mock RealtimeNotificationPort realtimeNotificationPort;

    TradingService service;

    static final DstInfo PAST_DST = new DstInfo(true,
            Instant.now().minusSeconds(3600),
            Instant.now().minusSeconds(1800));

    static final BigDecimal PRICE = new BigDecimal("22.00");

    static final AccountBalance NORMAL_BALANCE = new AccountBalance(
            10, new BigDecimal("20.00"), new BigDecimal("1000.00"));

    static final AccountBalance FRESH_BALANCE = new AccountBalance(
            0, BigDecimal.ZERO, new BigDecimal("1000.00"));

    static final AccountBalance LOW_BALANCE = new AccountBalance(
            0, BigDecimal.ZERO, BigDecimal.ZERO);

    // Account 10개 필드 생성자 (strategyType/strategyStatus/ticker/multiple 제거됨)
    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", "01",
            Account.Broker.KIS, Instant.now(), Instant.now()
    );

    // Strategy record - Account↔Strategy 분리 후 별도 엔티티
    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.StrategyType.INFINITE,
            Strategy.StrategyStatus.ACTIVE, Ticker.SOXL, BigDecimal.ONE,
            Instant.now(), Instant.now()
    );

    static final User USER = new User(
            ACCOUNT.userId(), "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
            null, null, null, Instant.now(), Instant.now(), null
    );

    @BeforeEach
    void setUp() {
        service = new TradingService(
                kisHolidayPort, kisAccountPort,
                kisPricePort, kisOrderPort, kisExecutionPort,
                tradingStrategy, correctionStrategy,
                tradeHistoryPort, portfolioSnapshotPort, notifyPort, userNotificationPort,
                orderPort, realtimeNotificationPort);
    }

    @Test
    void execute_normalFlow_allPortsCalledInOrder() throws InterruptedException {
        Order template = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLANNED, null);
        UUID plannedId = UUID.randomUUID();
        Order planned = new Order(plannedId, ACCOUNT.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 1, PRICE,
                Order.OrderStatus.PLANNED, null);
        Order placedOrder = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, "ORD-001");

        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(kisAccountPort.getBalance(ACCOUNT, Ticker.SOXL)).thenReturn(NORMAL_BALANCE);
        when(kisPricePort.getPrice(Ticker.SOXL, ACCOUNT)).thenReturn(PRICE);
        when(tradingStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(template));
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedOrder);
        when(kisExecutionPort.getExecutions(any(), eq(ACCOUNT))).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of());

        service.execute(STRATEGY, ACCOUNT, USER, PAST_DST);

        verify(kisHolidayPort).isMarketOpen(any(), eq(ACCOUNT));
        verify(kisAccountPort).getBalance(ACCOUNT, Ticker.SOXL);
        verify(kisPricePort).getPrice(Ticker.SOXL, ACCOUNT);
        verify(orderPort).saveAll(anyList());
        verify(orderPort).findPlannedByAccountAndDate(eq(ACCOUNT.id()), any());
        verify(kisOrderPort).place(any(), eq(ACCOUNT));
        verify(orderPort).markPlaced(eq(plannedId), eq("ORD-001"));
        verify(kisExecutionPort).getExecutions(any(), eq(ACCOUNT));
        verify(correctionStrategy).correct(any(), any(), any());
        verify(portfolioSnapshotPort).save(any(PortfolioSnapshot.class));
        verify(userNotificationPort).notifyTradingReport(eq(USER), eq(ACCOUNT), any(TradingReport.class));
    }

    @Test
    void execute_tradeHistories_savedForMainAndCorrectionOrders() throws InterruptedException {
        Order template = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLANNED, null);
        Order mainOrder = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, "ORD-001");
        Order corrOrder = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, null);

        UUID plannedId = UUID.randomUUID();
        Order planned = new Order(plannedId, ACCOUNT.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 1, PRICE,
                Order.OrderStatus.PLANNED, null);

        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(kisAccountPort.getBalance(ACCOUNT, Ticker.SOXL)).thenReturn(FRESH_BALANCE);
        when(kisPricePort.getPrice(Ticker.SOXL, ACCOUNT)).thenReturn(PRICE);
        when(tradingStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(template));
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(mainOrder, corrOrder);
        when(kisExecutionPort.getExecutions(any(), eq(ACCOUNT))).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of(corrOrder));

        service.execute(STRATEGY, ACCOUNT, USER, PAST_DST);

        verify(tradeHistoryPort, times(2)).save(any(TradeHistory.class));
    }

    @Test
    void execute_marketClosed_notifiesAndSkipsTrading() throws InterruptedException {
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(false);

        service.execute(STRATEGY, ACCOUNT, USER, PAST_DST);

        verify(notifyPort).notifyMarketClosed();
        verify(kisAccountPort, never()).getBalance(any(), any());
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(orderPort, never()).saveAll(any());
        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void execute_insufficientBalance_notifiesAndSkipsTrading() throws InterruptedException {
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(kisAccountPort.getBalance(ACCOUNT, Ticker.SOXL)).thenReturn(LOW_BALANCE);

        service.execute(STRATEGY, ACCOUNT, USER, PAST_DST);

        verify(notifyPort).notifyInsufficientBalance(ACCOUNT, LOW_BALANCE, Ticker.SOXL);
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(orderPort, never()).saveAll(any());
        verify(tradeHistoryPort, never()).save(any());
        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }
}
