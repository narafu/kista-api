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
    @Mock PlannedOrderPort plannedOrderPort; // 신규

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

    // Account 생성자: id, userId, nickname, accountNo, kisAppKey, kisSecretKey, kisAccountType,
    //                 strategyType, strategyStatus, telegramBotToken, telegramChatId,
    //                 symbol, exchangeCode, createdAt, updatedAt
    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", "01",
            StrategyType.INFINITE, StrategyStatus.ACTIVE,
            null, null, "SOXL", "AMS", Instant.now(), Instant.now()
    );

    // User 생성자: id, kakaoId, nickname, status, telegramBotToken, telegramChatId,
    //              createdAt, updatedAt, lastReappliedAt
    static final User USER = new User(
            ACCOUNT.userId(), "kakao-1", "홍길동", UserStatus.ACTIVE,
            null, null, Instant.now(), Instant.now(), null
    );

    @BeforeEach
    void setUp() {
        service = new TradingService(
                kisHolidayPort, kisAccountPort,
                kisPricePort, kisOrderPort, kisExecutionPort,
                tradingStrategy, correctionStrategy,
                tradeHistoryPort, portfolioSnapshotPort, notifyPort, userNotificationPort,
                plannedOrderPort); // 신규 인자
    }

    @Test
    void execute_normalFlow_allPortsCalledInOrder() throws InterruptedException {
        TradingVariables vars = new SoxlDivisionStrategy().calculate(NORMAL_BALANCE, PRICE);
        Order pendingOrder = new Order(LocalDate.now(), "SOXL", Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, null);
        Order placedOrder = new Order(LocalDate.now(), "SOXL", Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, "ORD-001");

        // DB에서 조회될 PENDING 주문 (id 필수: markExecuted에서 UUID로 사용)
        UUID plannedId = UUID.randomUUID();
        PlannedOrder planned = new PlannedOrder(plannedId, ACCOUNT.id(), LocalDate.now(),
                "SOXL", Order.OrderType.LOC, Order.OrderDirection.BUY, 1, PRICE,
                PlannedOrder.PlannedOrderStatus.PENDING, null);

        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(kisAccountPort.getBalance(ACCOUNT)).thenReturn(NORMAL_BALANCE);
        when(kisPricePort.getPrice("SOXL", ACCOUNT)).thenReturn(PRICE);
        when(tradingStrategy.calculate(NORMAL_BALANCE, PRICE)).thenReturn(vars);
        when(tradingStrategy.buildOrders(eq(vars), any(LocalDate.class), eq("SOXL")))
                .thenReturn(List.of(pendingOrder));
        when(plannedOrderPort.findPendingByAccountAndDate(eq(ACCOUNT.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedOrder);
        when(kisExecutionPort.getExecutions(any(), eq(ACCOUNT))).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of());

        service.execute(ACCOUNT, USER, PAST_DST);

        verify(kisHolidayPort).isMarketOpen(any(), eq(ACCOUNT));
        verify(kisAccountPort).getBalance(ACCOUNT);
        verify(kisPricePort).getPrice("SOXL", ACCOUNT);
        verify(tradingStrategy).calculate(NORMAL_BALANCE, PRICE);
        verify(plannedOrderPort).saveAll(anyList());                                      // 계획 저장
        verify(plannedOrderPort).findPendingByAccountAndDate(eq(ACCOUNT.id()), any());   // 실행 조회
        verify(kisOrderPort).place(any(), eq(ACCOUNT));
        verify(plannedOrderPort).markExecuted(eq(plannedId), eq("ORD-001"));             // 상태 갱신
        verify(kisExecutionPort).getExecutions(any(), eq(ACCOUNT));
        verify(correctionStrategy).correct(any(), any(), any());
        verify(portfolioSnapshotPort).save(any(PortfolioSnapshot.class));
        verify(userNotificationPort).notifyTradingReport(eq(USER), eq(ACCOUNT), any(TradingReport.class));
    }

    @Test
    void execute_tradeHistories_savedForMainAndCorrectionOrders() throws InterruptedException {
        TradingVariables vars = new SoxlDivisionStrategy().calculate(FRESH_BALANCE, PRICE);
        Order pendingOrder = new Order(LocalDate.now(), "SOXL", Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, null);
        Order mainOrder = new Order(LocalDate.now(), "SOXL", Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, "ORD-001");
        Order corrOrder = new Order(LocalDate.now(), "SOXL", Order.OrderType.LIMIT,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, null);

        UUID plannedId = UUID.randomUUID();
        PlannedOrder planned = new PlannedOrder(plannedId, ACCOUNT.id(), LocalDate.now(),
                "SOXL", Order.OrderType.LOC, Order.OrderDirection.BUY, 1, PRICE,
                PlannedOrder.PlannedOrderStatus.PENDING, null);

        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(kisAccountPort.getBalance(ACCOUNT)).thenReturn(FRESH_BALANCE);
        when(kisPricePort.getPrice("SOXL", ACCOUNT)).thenReturn(PRICE);
        when(tradingStrategy.calculate(FRESH_BALANCE, PRICE)).thenReturn(vars);
        when(tradingStrategy.buildOrders(eq(vars), any(LocalDate.class), eq("SOXL")))
                .thenReturn(List.of(pendingOrder));
        when(plannedOrderPort.findPendingByAccountAndDate(eq(ACCOUNT.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(mainOrder, corrOrder);
        when(kisExecutionPort.getExecutions(any(), eq(ACCOUNT))).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of(corrOrder));

        service.execute(ACCOUNT, USER, PAST_DST);

        verify(tradeHistoryPort, times(2)).save(any(TradeHistory.class));
    }

    @Test
    void execute_marketClosed_notifiesAndSkipsTrading() throws InterruptedException {
        // isMarketOpen이 waitForOrderTime보다 먼저 → 휴장 시 plan도 저장 안 됨
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(false);

        service.execute(ACCOUNT, USER, PAST_DST);

        verify(notifyPort).notifyMarketClosed();
        verify(kisAccountPort, never()).getBalance(any());
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(plannedOrderPort, never()).saveAll(any()); // 계획 주문도 저장 안 됨
        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void execute_insufficientBalance_notifiesAndSkipsTrading() throws InterruptedException {
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(kisAccountPort.getBalance(ACCOUNT)).thenReturn(LOW_BALANCE);

        service.execute(ACCOUNT, USER, PAST_DST);

        verify(notifyPort).notifyInsufficientBalance(LOW_BALANCE);
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(plannedOrderPort, never()).saveAll(any()); // 잔고 부족 시 계획 주문도 없음
        verify(tradeHistoryPort, never()).save(any());
        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }
}
