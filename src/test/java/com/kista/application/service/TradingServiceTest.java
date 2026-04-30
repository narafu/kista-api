package com.kista.application.service;

import com.kista.domain.model.*;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CorrectionStrategy;
import com.kista.domain.strategy.SoxlDivisionStrategy;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingServiceTest {

    @Mock KisTokenPort kisTokenPort;
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

    TradingService service;

    // 과거 시점 DstInfo: sleep 없이 즉시 통과
    static final DstInfo PAST_DST = new DstInfo(true,
            Instant.now().minusSeconds(3600),
            Instant.now().minusSeconds(1800));

    static final String TOKEN = "test-token";
    static final BigDecimal PRICE = new BigDecimal("22.00");

    // 보유 수량 있는 잔고 (shouldSkip=false, t=5 → BUY+SELL LOC 2건)
    static final AccountBalance NORMAL_BALANCE = new AccountBalance(
            10, new BigDecimal("20.00"), new BigDecimal("500.00"), new BigDecimal("1000.00"));

    // 보유 없는 잔고 (shouldSkip=false, t=0 → BUY LOC 1건만)
    static final AccountBalance FRESH_BALANCE = new AccountBalance(
            0, BigDecimal.ZERO, new BigDecimal("500.00"), new BigDecimal("1000.00"));

    // 수량 0 & 예수금 0 (shouldSkip=true)
    static final AccountBalance LOW_BALANCE = new AccountBalance(
            0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    @BeforeEach
    void setUp() {
        service = new TradingService("SOXL",
                kisTokenPort, kisHolidayPort, kisAccountPort,
                kisPricePort, kisOrderPort, kisExecutionPort,
                tradingStrategy, correctionStrategy,
                tradeHistoryPort, portfolioSnapshotPort, notifyPort);
    }

    @Test
    void execute_normalFlow_allPortsCalledInOrder() throws InterruptedException {
        TradingVariables vars = new SoxlDivisionStrategy().calculate(NORMAL_BALANCE, PRICE);
        Order pendingOrder = new Order(LocalDate.now(), "SOXL", Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, null);
        Order placedOrder = new Order(LocalDate.now(), "SOXL", Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, "ORD-001");

        when(kisTokenPort.getToken()).thenReturn(TOKEN);
        when(kisHolidayPort.isMarketOpen(eq(TOKEN), any())).thenReturn(true);
        when(kisAccountPort.getBalance(TOKEN)).thenReturn(NORMAL_BALANCE);
        when(kisPricePort.getPrice(TOKEN, "SOXL")).thenReturn(PRICE);
        when(tradingStrategy.calculate(NORMAL_BALANCE, PRICE)).thenReturn(vars);
        when(tradingStrategy.buildOrders(eq(vars), any(LocalDate.class), eq("SOXL"))).thenReturn(List.of(pendingOrder));
        when(kisOrderPort.place(eq(TOKEN), any())).thenReturn(placedOrder);
        when(kisExecutionPort.getExecutions(eq(TOKEN), any())).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of());

        service.execute(PAST_DST);

        verify(kisHolidayPort).isMarketOpen(eq(TOKEN), any());
        verify(kisAccountPort).getBalance(TOKEN);
        verify(kisPricePort).getPrice(TOKEN, "SOXL");
        verify(tradingStrategy).calculate(NORMAL_BALANCE, PRICE);
        verify(kisExecutionPort).getExecutions(eq(TOKEN), any());
        verify(correctionStrategy).correct(any(), any(), any());
        verify(portfolioSnapshotPort).save(any(PortfolioSnapshot.class));
        verify(notifyPort).notifyReport(any(TradingReport.class));
    }

    @Test
    void execute_tradeHistories_savedForMainAndCorrectionOrders() throws InterruptedException {
        // FRESH_BALANCE: q=0, t=0 → buildOrders가 BUY 1건 반환, save = main 1 + corr 1 = 2
        TradingVariables vars = new SoxlDivisionStrategy().calculate(FRESH_BALANCE, PRICE);
        Order pendingOrder = new Order(LocalDate.now(), "SOXL", Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, null);
        Order mainOrder = new Order(LocalDate.now(), "SOXL", Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, "ORD-001");
        Order corrOrder = new Order(LocalDate.now(), "SOXL", Order.OrderType.LIMIT,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, null);

        when(kisTokenPort.getToken()).thenReturn(TOKEN);
        when(kisHolidayPort.isMarketOpen(eq(TOKEN), any())).thenReturn(true);
        when(kisAccountPort.getBalance(TOKEN)).thenReturn(FRESH_BALANCE);
        when(kisPricePort.getPrice(TOKEN, "SOXL")).thenReturn(PRICE);
        when(tradingStrategy.calculate(FRESH_BALANCE, PRICE)).thenReturn(vars);
        when(tradingStrategy.buildOrders(eq(vars), any(LocalDate.class), eq("SOXL"))).thenReturn(List.of(pendingOrder));
        when(kisOrderPort.place(eq(TOKEN), any())).thenReturn(mainOrder, corrOrder);
        when(kisExecutionPort.getExecutions(eq(TOKEN), any())).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of(corrOrder));

        service.execute(PAST_DST);

        // mainOrder 1개 + corrOrder 1개 = 총 2번 save
        verify(tradeHistoryPort, times(2)).save(any(TradeHistory.class));
    }

    @Test
    void execute_marketClosed_notifiesAndSkipsTrading() throws InterruptedException {
        when(kisTokenPort.getToken()).thenReturn(TOKEN);
        when(kisHolidayPort.isMarketOpen(eq(TOKEN), any())).thenReturn(false);

        service.execute(PAST_DST);

        verify(notifyPort).notifyMarketClosed();
        verify(kisAccountPort, never()).getBalance(any());
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(notifyPort, never()).notifyReport(any());
    }

    @Test
    void execute_insufficientBalance_notifiesAndSkipsTrading() throws InterruptedException {
        when(kisTokenPort.getToken()).thenReturn(TOKEN);
        when(kisHolidayPort.isMarketOpen(eq(TOKEN), any())).thenReturn(true);
        when(kisAccountPort.getBalance(TOKEN)).thenReturn(LOW_BALANCE);

        service.execute(PAST_DST);

        verify(notifyPort).notifyInsufficientBalance(LOW_BALANCE);
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(tradeHistoryPort, never()).save(any());
        verify(notifyPort, never()).notifyReport(any());
    }
}
