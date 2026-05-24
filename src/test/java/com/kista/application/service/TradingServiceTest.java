package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.model.order.*;
import com.kista.domain.model.kis.*;
import com.kista.domain.model.user.*;
import com.kista.domain.port.in.ExecuteTradingUseCase.BatchContext;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingServiceTest {

    @Mock KisHolidayPort kisHolidayPort;
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
    @Mock TradingCycleHistoryRepository cycleHistoryRepository;

    TradingService service;

    static final DstInfo PAST_DST = new DstInfo(true,
            Instant.now().minusSeconds(3600),
            Instant.now().minusSeconds(1800));

    static final BigDecimal PRICE = new BigDecimal("22.00");

    static final AccountBalance LOW_BALANCE = new AccountBalance(0, null, BigDecimal.ZERO);

    // Account 10개 필드 생성자 (strategyType/strategyStatus/ticker/multiple 제거됨)
    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", "01",
            Account.Broker.KIS, Instant.now(), Instant.now()
    );

    // TradingCycle record
    static final TradingCycle CYCLE = new TradingCycle(
            UUID.randomUUID(), ACCOUNT.id(), TradingCycle.Type.INFINITE,
            TradingCycle.Status.ACTIVE, Ticker.SOXL, BigDecimal.ONE,
            null, Instant.now(), Instant.now()
    );

    // TradingCycleHistory 기반 잔고 (TradingService가 KIS API 대신 이력에서 읽음)
    static final TradingCycleHistory NORMAL_HISTORY = new TradingCycleHistory(
            null, CYCLE.id(), new BigDecimal("1000.00"), new BigDecimal("20.00"), BigDecimal.TEN, null);
    static final TradingCycleHistory FRESH_HISTORY = new TradingCycleHistory(
            null, CYCLE.id(), new BigDecimal("1000.00"), null, BigDecimal.ZERO, null);
    static final TradingCycleHistory LOW_HISTORY = new TradingCycleHistory(
            null, CYCLE.id(), BigDecimal.ZERO, null, BigDecimal.ZERO, null);

    static final User USER = new User(
            ACCOUNT.userId(), "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
            null, null, null, Instant.now(), Instant.now(), null, NotificationChannel.TELEGRAM
    );

    @BeforeEach
    void setUp() {
        service = new TradingService(
                kisHolidayPort,
                kisPricePort, kisOrderPort, kisExecutionPort,
                tradingStrategy, correctionStrategy,
                tradeHistoryPort, portfolioSnapshotPort, notifyPort, userNotificationPort,
                orderPort, realtimeNotificationPort, cycleHistoryRepository);
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
        when(cycleHistoryRepository.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(kisPricePort.getPrice(Ticker.SOXL, ACCOUNT)).thenReturn(PRICE);
        when(tradingStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(template));
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedOrder);
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of());

        service.execute(CYCLE, ACCOUNT, USER, PAST_DST);

        verify(kisHolidayPort).isMarketOpen(any(), eq(ACCOUNT));
        verify(cycleHistoryRepository).findRecentByCycleId(CYCLE.id(), 1);
        verify(kisPricePort).getPrice(Ticker.SOXL, ACCOUNT);
        verify(orderPort).saveAll(anyList());
        verify(orderPort).findPlannedByAccountAndDate(eq(ACCOUNT.id()), any());
        verify(kisOrderPort).place(any(), eq(ACCOUNT));
        verify(orderPort).markPlaced(eq(plannedId), eq("ORD-001"));
        verify(kisExecutionPort).getExecutions(any(), any(), any(), eq(ACCOUNT));
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
        when(cycleHistoryRepository.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(FRESH_HISTORY));
        when(kisPricePort.getPrice(Ticker.SOXL, ACCOUNT)).thenReturn(PRICE);
        when(tradingStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(template));
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(mainOrder, corrOrder);
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of(corrOrder));

        service.execute(CYCLE, ACCOUNT, USER, PAST_DST);

        verify(tradeHistoryPort, times(2)).save(any(TradeHistory.class));
    }

    @Test
    void execute_marketClosed_notifiesAndSkipsTrading() throws InterruptedException {
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(false);

        service.execute(CYCLE, ACCOUNT, USER, PAST_DST);

        verify(notifyPort).notifyMarketClosed();
        verify(cycleHistoryRepository, never()).findRecentByCycleId(any(), anyInt());
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(orderPort, never()).saveAll(any());
        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void execute_insufficientBalance_notifiesAndSkipsTrading() throws InterruptedException {
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(cycleHistoryRepository.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(LOW_HISTORY));

        service.execute(CYCLE, ACCOUNT, USER, PAST_DST);

        verify(notifyPort).notifyInsufficientBalance(eq(ACCOUNT), eq(LOW_BALANCE), eq(Ticker.SOXL));
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(orderPort, never()).saveAll(any());
        verify(tradeHistoryPort, never()).save(any());
        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    // ── executeBatch 테스트 ────────────────────────────────────────────────────

    @Test
    void executeBatch_fetchesPricesOnce_notPerCycle() throws InterruptedException {
        // 두 사이클이 같은 ticker → getPrices() 1회, getPrice() 0회
        TradingCycle cycle2 = new TradingCycle(UUID.randomUUID(), ACCOUNT.id(),
                TradingCycle.Type.INFINITE, TradingCycle.Status.ACTIVE, Ticker.SOXL, BigDecimal.ONE,
                null, Instant.now(), Instant.now());
        TradingCycleHistory history2 = new TradingCycleHistory(
                null, cycle2.id(), new BigDecimal("1000.00"), new BigDecimal("20.00"), BigDecimal.TEN, null);

        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(cycleHistoryRepository.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(cycleHistoryRepository.findRecentByCycleId(cycle2.id(), 1)).thenReturn(List.of(history2));
        when(tradingStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of());

        service.executeBatch(List.of(
                new BatchContext(CYCLE, ACCOUNT, USER),
                new BatchContext(cycle2, ACCOUNT, USER)
        ), PAST_DST);

        verify(kisPricePort, times(1)).getPrices(anyList(), eq(ACCOUNT));
        verify(kisPricePort, never()).getPrice(any(), any());
    }

    @Test
    void executeBatch_oneCycleFails_continuesWithNextAndNotifiesAdmin() throws InterruptedException {
        // CYCLE → 예외 발생, cycle2 → 정상 실행
        TradingCycle cycle2 = new TradingCycle(UUID.randomUUID(), ACCOUNT.id(),
                TradingCycle.Type.INFINITE, TradingCycle.Status.ACTIVE, Ticker.TQQQ, BigDecimal.ONE,
                null, Instant.now(), Instant.now());
        TradingCycleHistory history2 = new TradingCycleHistory(
                null, cycle2.id(), new BigDecimal("1000.00"), new BigDecimal("20.00"), BigDecimal.TEN, null);

        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, PRICE, Ticker.TQQQ, PRICE));
        // CYCLE: 휴장일 체크 전 잔고 조회에서 예외 (이미 isMarketOpen 다음이므로 isMarketOpen도 stub 필요)
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        RuntimeException ex = new RuntimeException("잔고 조회 오류");
        when(cycleHistoryRepository.findRecentByCycleId(CYCLE.id(), 1)).thenThrow(ex);
        when(cycleHistoryRepository.findRecentByCycleId(cycle2.id(), 1)).thenReturn(List.of(history2));
        when(tradingStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of());

        service.executeBatch(List.of(
                new BatchContext(CYCLE, ACCOUNT, USER),
                new BatchContext(cycle2, ACCOUNT, USER)
        ), PAST_DST);

        verify(notifyPort).notifyError(ex);
        // cycle2는 정상 실행 → portfolioSnapshotPort.save 호출 확인
        verify(portfolioSnapshotPort).save(any(PortfolioSnapshot.class));
    }

    @Test
    void executeBatch_getPricesFails_fallsBackToGetPrice() throws InterruptedException {
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenThrow(new RuntimeException("API 오류"));
        when(kisPricePort.getPrice(Ticker.SOXL, ACCOUNT)).thenReturn(PRICE);
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(cycleHistoryRepository.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(tradingStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(kisPricePort).getPrice(Ticker.SOXL, ACCOUNT); // fallback 호출 확인
    }
}
