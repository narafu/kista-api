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
import com.kista.domain.port.in.GetNextOrdersUseCase;
import com.kista.domain.port.in.GetNextOrdersUseCase.SkipReason;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CorrectionStrategy;
import com.kista.domain.strategy.InfiniteTradingStrategy;
import com.kista.domain.strategy.PrivacyTradingStrategy;
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
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingServiceTest {

    @Mock KisHolidayPort kisHolidayPort;
    @Mock KisPricePort kisPricePort;
    @Mock KisOrderPort kisOrderPort;
    @Mock KisExecutionPort kisExecutionPort;
    @Mock InfiniteTradingStrategy infiniteStrategy;
    @Mock PrivacyTradingStrategy privacyStrategy;
    @Mock CorrectionStrategy correctionStrategy;
    @Mock TradeHistoryPort tradeHistoryPort;
    @Mock PortfolioSnapshotPort portfolioSnapshotPort;
    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort;
    @Mock OrderPort orderPort;
    @Mock RealtimeNotificationPort realtimeNotificationPort;
    @Mock TradingCycleHistoryPort cycleHistoryPort;
    @Mock AccountPort accountPort;
    @Mock TradingCyclePort cyclePort;
    @Mock PrivacyTradePort privacyTradePort;

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
            TradingCycle.Status.ACTIVE, Ticker.SOXL, null,
            Instant.now(), Instant.now()
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
                infiniteStrategy, privacyStrategy, correctionStrategy,
                tradeHistoryPort, portfolioSnapshotPort, notifyPort, userNotificationPort,
                orderPort, realtimeNotificationPort, cycleHistoryPort,
                accountPort, cyclePort, privacyTradePort);
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
        when(cycleHistoryPort.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(template));
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedOrder);
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of());

        service.execute(CYCLE, ACCOUNT, USER, PAST_DST);

        verify(kisHolidayPort).isMarketOpen(any(), eq(ACCOUNT));
        verify(cycleHistoryPort).findRecentByCycleId(CYCLE.id(), 1);
        verify(kisPricePort, never()).getPrice(any(), any()); // 단건 경로: 가격은 executeBatch가 배치 조회
        verify(orderPort).saveAll(anyList());
        verify(orderPort).findPlannedByAccountAndDate(eq(ACCOUNT.id()), any());
        verify(kisOrderPort).place(any(), eq(ACCOUNT));
        verify(orderPort).markPlaced(eq(plannedId), eq("ORD-001"));
        verify(kisExecutionPort).getExecutions(any(), any(), any(), eq(ACCOUNT));
        verify(correctionStrategy).correct(any(), any(), any());
        verify(portfolioSnapshotPort, never()).save(any()); // price=null → 포트폴리오 스냅샷 생략
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

        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(cycleHistoryPort.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(FRESH_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(template));
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(mainOrder, corrOrder);
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(correctionStrategy.correct(any(), any(), any())).thenReturn(List.of(corrOrder));

        // holdings=0 신규 계좌는 executeBatch 경로(getPrices)로만 가격 수신 가능
        service.executeBatch(List.of(new BatchContext(CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(tradeHistoryPort, times(2)).save(any(TradeHistory.class));
    }

    @Test
    void execute_marketClosed_notifiesAndSkipsTrading() throws InterruptedException {
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(false);

        service.execute(CYCLE, ACCOUNT, USER, PAST_DST);

        verify(notifyPort).notifyMarketClosed();
        verify(cycleHistoryPort, never()).findRecentByCycleId(any(), anyInt());
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(orderPort, never()).saveAll(any());
        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void execute_insufficientBalance_notifiesAndSkipsTrading() throws InterruptedException {
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(cycleHistoryPort.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(LOW_HISTORY));

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
                TradingCycle.Type.INFINITE, TradingCycle.Status.ACTIVE, Ticker.SOXL, null,
                Instant.now(), Instant.now());
        TradingCycleHistory history2 = new TradingCycleHistory(
                null, cycle2.id(), new BigDecimal("1000.00"), new BigDecimal("20.00"), BigDecimal.TEN, null);

        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(cycleHistoryPort.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(cycleHistoryPort.findRecentByCycleId(cycle2.id(), 1)).thenReturn(List.of(history2));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
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
                TradingCycle.Type.INFINITE, TradingCycle.Status.ACTIVE, Ticker.TQQQ, null,
                Instant.now(), Instant.now());
        TradingCycleHistory history2 = new TradingCycleHistory(
                null, cycle2.id(), new BigDecimal("1000.00"), new BigDecimal("20.00"), BigDecimal.TEN, null);

        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, PRICE, Ticker.TQQQ, PRICE));
        // CYCLE: 휴장일 체크 전 잔고 조회에서 예외 (이미 isMarketOpen 다음이므로 isMarketOpen도 stub 필요)
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        RuntimeException ex = new RuntimeException("잔고 조회 오류");
        when(cycleHistoryPort.findRecentByCycleId(CYCLE.id(), 1)).thenThrow(ex);
        when(cycleHistoryPort.findRecentByCycleId(cycle2.id(), 1)).thenReturn(List.of(history2));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
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
    void executeBatch_getPricesFails_cycleFailsAndNotifiesAdmin() throws InterruptedException {
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenThrow(new RuntimeException("API 오류"));
        // getPrice 단건 fallback도 실패(null 반환) → price=null → holdings=0 → IllegalStateException
        when(kisHolidayPort.isMarketOpen(any(), eq(ACCOUNT))).thenReturn(true);
        when(cycleHistoryPort.findRecentByCycleId(CYCLE.id(), 1)).thenReturn(List.of(FRESH_HISTORY));

        service.executeBatch(List.of(new BatchContext(CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(kisPricePort).getPrice(Ticker.SOXL, ACCOUNT); // 단건 fallback 시도 확인
        verify(notifyPort).notifyError(any(IllegalStateException.class)); // 현재가 null → 실패
        verify(tradeHistoryPort, never()).save(any()); // 현재가 조회 실패로 주문 없음
    }

    // ── preview 테스트 ────────────────────────────────────────────────────────

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
        // execute()와 달리 savePlannedOrders 미호출
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
    void preview_returnsSkipUnsupportedStrategy_whenPrivacy() {
        TradingCycle privacyCycle = new TradingCycle(
                UUID.randomUUID(), ACCOUNT.id(), TradingCycle.Type.PRIVACY,
                TradingCycle.Status.ACTIVE, Ticker.SOXL, null,
                Instant.now(), Instant.now());

        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        when(cyclePort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(privacyCycle));
        when(cycleHistoryPort.findRecentByCycleId(privacyCycle.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));

        GetNextOrdersUseCase.Result result = service.preview(ACCOUNT.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(SkipReason.UNSUPPORTED_STRATEGY);
        assertThat(result.position()).isNull();
        assertThat(result.orders()).isEmpty();
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
