package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.order.*;
import com.kista.domain.model.kis.*;
import com.kista.domain.model.user.*;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.KisPricePort.PriceSnapshot;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.domain.strategy.InfiniteTradingStrategy;
import com.kista.domain.strategy.PrivacyCycleOrderStrategy;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingServiceTest {

    @Mock MarketCalendarPort marketCalendarPort;
    @Mock KisPricePort kisPricePort;
    @Mock KisOrderPort kisOrderPort;
    @Mock KisExecutionPort kisExecutionPort;
    @Mock InfiniteTradingStrategy infiniteStrategy;
    @Mock PrivacyTradingStrategy privacyStrategy;
    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort;
    @Mock OrderPort orderPort;
    @Mock RealtimeNotificationPort realtimeNotificationPort;
    @Mock CyclePositionPort cycleHistoryPort;
    @Mock StrategyPort cyclePort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock KisMarginPort kisMarginPort;
    TradingService service;

    static final DstInfo PAST_DST = new DstInfo(true,
            Instant.now().minusSeconds(3600),
            Instant.now().minusSeconds(1800));

    static final BigDecimal PRICE = new BigDecimal("22.00");

    // 잔액 $10, 평단 $20, 보유 5주 — buildOrders가 $20 매수 주문 반환 시 매수금액($20) > 잔액($10) → skip
    static final AccountBalance LOW_BALANCE = new AccountBalance(5, new BigDecimal("20.00"), new BigDecimal("10.00"));

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", "01",
            Account.Broker.KIS
    );

    // Strategy + StrategyCycle — 기존 TradingCycle을 두 레이어로 분리
    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE
    );
    static final StrategyCycle STRATEGY_CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null
    );

    // CyclePosition 기반 잔고 (TradingService가 KIS API 대신 이력에서 읽음)
    static final CyclePosition NORMAL_HISTORY = new CyclePosition(
            null, STRATEGY_CYCLE.id(), new BigDecimal("1000.00"), new BigDecimal("22.00"), new BigDecimal("20.00"), 10, null, null);
    static final CyclePosition FRESH_HISTORY = new CyclePosition(
            null, STRATEGY_CYCLE.id(), new BigDecimal("1000.00"), null, null, 0, null, null);
    static final CyclePosition LOW_HISTORY = new CyclePosition(
            null, STRATEGY_CYCLE.id(), new BigDecimal("10.00"), new BigDecimal("22.00"), new BigDecimal("20.00"), 5, null, null);

    static final User USER = new User(
            ACCOUNT.userId(), "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
            null, null, null, null, NotificationChannel.TELEGRAM
    );

    @BeforeEach
    void setUp() {
        // 헬퍼 컴포넌트는 실제 인스턴스로 생성 — 기존 mock(cycleHistoryPort, infiniteStrategy 등)이 그대로 동작
        TradingBalanceLoader balanceLoader = new TradingBalanceLoader(cycleHistoryPort);
        TradingOrderPlanner orderPlanner = new TradingOrderPlanner(orderPort);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy),
                new PrivacyCycleOrderStrategy(privacyStrategy)));
        CycleOrderComputer orderComputer = new CycleOrderComputer(cycleStrategies);
        // CycleRotationService: kisMarginPort, strategyPort, strategyCyclePort, cyclePositionPort, notifyPort, userNotificationPort, cycleStrategies
        CycleRotationService rotationService = new CycleRotationService(
                kisMarginPort, cyclePort, strategyCyclePort, cycleHistoryPort, notifyPort, userNotificationPort, cycleStrategies);
        TradingPriceFetcher priceFetcher = new TradingPriceFetcher(kisPricePort);
        BuyOrderPriceCapper priceCapper = new BuyOrderPriceCapper(orderPort, orderPlanner);
        TradingOrderExecutor orderExecutor = new TradingOrderExecutor(orderPort, kisOrderPort, priceCapper);
        TradingReporter reporter = new TradingReporter(
                kisExecutionPort, orderPort, userNotificationPort, realtimeNotificationPort,
                cycleHistoryPort, strategyCyclePort, rotationService);
        service = new TradingService(
                marketCalendarPort, notifyPort,
                orderPort, privacyTradePort, strategyCyclePort,
                balanceLoader, orderComputer, orderPlanner,
                priceFetcher, orderExecutor, reporter);
    }

    @Test
    void execute_normalFlow_allPortsCalledInOrder() throws InterruptedException {
        BigDecimal startPrice = new BigDecimal("20.00"); // 시작가 (Phase A, 04:00 KST)
        BigDecimal prevClose = new BigDecimal("19.00");  // 전일종가
        // PRICE = "22.00" — 종가 (PostClose 이후)

        Order template = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, startPrice, Order.OrderStatus.PLANNED, null, null, null);
        UUID plannedId = UUID.randomUUID();
        Order planned = new Order(plannedId, ACCOUNT.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 1, startPrice,
                Order.OrderStatus.PLANNED, null, null, null);
        Order placedOrder = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, startPrice, Order.OrderStatus.PLACED, "ORD-001", null, null);

        when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(startPrice, prevClose))); // 시작가+전일종가
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, PRICE)); // 종가
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(template));
        when(orderPort.findPlannedOrPlacedByAccountAndDate(eq(ACCOUNT.id()), any(LocalDate.class)))
                .thenReturn(List.of()); // 오늘 주문 없음 → 신규 계산
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedOrder);
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.execute(STRATEGY, ACCOUNT, USER, PAST_DST);

        verify(marketCalendarPort).isMarketOpen(any());
        verify(cycleHistoryPort).findLatestByStrategyId(STRATEGY.id(), 1);
        verify(kisPricePort, never()).getPriceSnapshot(any(), any()); // 단건 fallback 없음 — getPriceSnapshots 성공
        verify(kisPricePort, never()).getPrice(any(), any());         // 단건 fallback 없음
        verify(kisPricePort).getPriceSnapshots(anyList(), eq(ACCOUNT)); // 시작가(Phase A) 1회
        verify(kisPricePort).getPrices(anyList(), eq(ACCOUNT));         // 종가(PostClose) 1회
        verify(orderPort).saveAll(anyList());
        verify(orderPort, atLeastOnce()).findPlannedByAccountAndDate(eq(ACCOUNT.id()), any());
        verify(kisOrderPort).place(any(), eq(ACCOUNT));
        verify(orderPort).markPlaced(eq(plannedId), eq("ORD-001"));
        verify(kisExecutionPort).getExecutions(any(), any(), any(), eq(ACCOUNT));
        // 종가(PRICE="22.00")가 저장되어야 함 — 시작가("20.00")가 저장되면 버그
        verify(cycleHistoryPort).save(argThat(h -> h.closingPrice() != null
                && h.closingPrice().compareTo(PRICE) == 0));
        verify(userNotificationPort).notifyTradingReport(eq(USER), eq(ACCOUNT), any(TradingReport.class));
    }

    @Test
    void executeBatch_todayOrdersExist_skipsPlanningAndProceedsToKis() throws InterruptedException {
        // 수동 실행으로 이미 PLANNED 주문이 존재 → 재계산 skip, KIS 접수만 수행
        Order alreadyPlanned = new Order(UUID.randomUUID(), ACCOUNT.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLANNED, null, null, null);
        Order placedOrder = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, "ORD-001", null, null);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        // 오늘 이미 PLANNED 주문 존재 → planAndSaveOrders에서 재계산 skip
        when(orderPort.findPlannedOrPlacedByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(List.of(alreadyPlanned));
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(List.of(alreadyPlanned));
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedOrder);
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 재계산 없음 — saveAll 미호출
        verify(orderPort, never()).saveAll(any());
        // KIS 접수는 정상 수행
        verify(kisOrderPort).place(any(), eq(ACCOUNT));
        verify(kisExecutionPort).getExecutions(any(), any(), any(), eq(ACCOUNT));
    }

    @Test
    void execute_marketClosed_notifiesAndSkipsTrading() throws InterruptedException {
        // 휴장 확인이 executeBatch() 최상단으로 이동 → 가격 조회 전 조기 반환
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(false);

        service.execute(STRATEGY, ACCOUNT, USER, PAST_DST);

        verify(marketCalendarPort).isMarketOpen(any());
        verify(notifyPort).notifyMarketClosed();
        verify(kisPricePort, never()).getPrices(anyList(), any()); // 휴장 시 KIS 가격 조회 생략
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(cycleHistoryPort, never()).findLatestByStrategyId(any(), anyInt());
        verify(orderPort, never()).saveAll(any());
        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void execute_insufficientBalance_notifiesAndSkipsTrading() throws InterruptedException {
        // 매수금액($20) > 잔액($10) → skip + notify
        Order overBudgetBuy = new Order(null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED, null, null, null);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        // 잔액 부족 시 조기 반환 → getPrices(종가 조회)까지 도달하지 않음
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(LOW_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of(overBudgetBuy));

        service.execute(STRATEGY, ACCOUNT, USER, PAST_DST);

        verify(notifyPort).notifyInsufficientBalance(eq(ACCOUNT), eq(LOW_BALANCE), eq(Ticker.SOXL));
        verify(kisPricePort, never()).getPrice(any(), any()); // 단건 fallback 없음 — getPrices 성공
        verify(orderPort, never()).saveAll(any());
        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void execute_withBuyExecution_savesPostTradeBalanceAndNotifiesPostTradeSnapshot() throws InterruptedException {
        // 0회차(holdings=0) → 1주 매수 체결 → 이력: holdings=1, avgPrice=체결가 / 알림: 보유 1주
        BigDecimal startPrice = new BigDecimal("20.00");
        BigDecimal closingPrice = new BigDecimal("22.00"); // PRICE
        BigDecimal executionPrice = new BigDecimal("20.50"); // LOC 체결가 (개장가~종가 사이)
        BigDecimal executionAmount = new BigDecimal("20.50"); // 1주 × $20.50

        Order template = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, startPrice, Order.OrderStatus.PLANNED, null, null, null);
        UUID plannedId = UUID.randomUUID();
        Order planned = new Order(plannedId, ACCOUNT.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 1, startPrice,
                Order.OrderStatus.PLANNED, null, null, null);
        Order placedOrder = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, startPrice, Order.OrderStatus.PLACED, "ORD-001", null, null);
        Execution buyExecution = new Execution(LocalDate.now(), Ticker.SOXL,
                Order.OrderDirection.BUY, 1, executionPrice, executionAmount, "ORD-001");

        when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(startPrice, new BigDecimal("19.00")))); // 시작가+전일종가
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, closingPrice)); // 종가
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(FRESH_HISTORY)); // holdings=0
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(template));
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedOrder);
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT)))
                .thenReturn(List.of(buyExecution));

        service.execute(STRATEGY, ACCOUNT, USER, PAST_DST);

        // 이력: holdings=1, avgPrice=체결가, closingPrice=종가 저장 (버그 #2 수정 검증)
        verify(cycleHistoryPort).save(argThat(h ->
                h.holdings() == 1
                && h.avgPrice() != null && h.avgPrice().compareTo(executionPrice) == 0
                && h.closingPrice() != null && h.closingPrice().compareTo(closingPrice) == 0));
        // 알림: 보유 1주 (pre-trade 0주 아님) (버그 #1 수정 검증)
        verify(userNotificationPort).notifyTradingReport(eq(USER), eq(ACCOUNT),
                argThat(r -> r.snapshot().holdings() == 1));
    }

    // ── executeBatch 테스트 ────────────────────────────────────────────────────

    @Test
    void executeBatch_fetchesPricesTwice_startAndClose_notPerCycle() throws InterruptedException {
        // 두 전략이 같은 ticker → getPriceSnapshots() 1회(시작가), getPrices() 1회(종가), 단건 fallback 없음
        Strategy strategy2 = new Strategy(UUID.randomUUID(), ACCOUNT.id(),
                Strategy.Type.INFINITE, Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyCycle cycle2 = new StrategyCycle(UUID.randomUUID(), strategy2.id(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        CyclePosition history2 = new CyclePosition(
                null, cycle2.id(), new BigDecimal("1000.00"), new BigDecimal("20.00"), new BigDecimal("20.00"), 10, null, null);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(cycleHistoryPort.findLatestByStrategyId(strategy2.id(), 1)).thenReturn(List.of(history2));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(
                new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER),
                new BatchContext(strategy2, cycle2, ACCOUNT, USER)
        ), PAST_DST);

        verify(kisPricePort).getPriceSnapshots(anyList(), eq(ACCOUNT)); // 시작가(Phase A) 1회
        verify(kisPricePort).getPrices(anyList(), eq(ACCOUNT));         // 종가(PostClose) 1회
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(kisPricePort, never()).getPriceSnapshot(any(), any());
    }

    @Test
    void executeBatch_oneCycleFails_continuesWithNextAndNotifiesAdmin() throws InterruptedException {
        // STRATEGY → 예외 발생, strategy2 → 정상 실행
        Strategy strategy2 = new Strategy(UUID.randomUUID(), ACCOUNT.id(),
                Strategy.Type.INFINITE, Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle cycle2 = new StrategyCycle(UUID.randomUUID(), strategy2.id(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        CyclePosition history2 = new CyclePosition(
                null, cycle2.id(), new BigDecimal("1000.00"), new BigDecimal("20.00"), new BigDecimal("20.00"), 10, null, null);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE), Ticker.TQQQ, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE, Ticker.TQQQ, PRICE));
        // STRATEGY: 잔고 조회에서 예외
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        RuntimeException ex = new RuntimeException("잔고 조회 오류");
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenThrow(ex);
        when(cycleHistoryPort.findLatestByStrategyId(strategy2.id(), 1)).thenReturn(List.of(history2));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(
                new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER),
                new BatchContext(strategy2, cycle2, ACCOUNT, USER)
        ), PAST_DST);

        verify(notifyPort).notifyError(ex);
        // strategy2는 정상 실행 → cycleHistoryPort.save 호출 확인
        verify(cycleHistoryPort, atLeastOnce()).save(any());
    }

    @Test
    void executeBatch_getPricesFails_cycleFailsAndNotifiesAdmin() throws InterruptedException {
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenThrow(new RuntimeException("API 오류"));
        // getPriceSnapshot 단건 fallback도 실패 → snapshot=null → price=null + prevClosePrice=null → holdings=0 → IllegalStateException
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(FRESH_HISTORY));

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(kisPricePort).getPriceSnapshot(Ticker.SOXL, ACCOUNT); // 단건 fallback 시도 확인
        verify(notifyPort).notifyError(any(IllegalStateException.class)); // 현재가·전일종가 null → 실패
    }

    // ── 연속 정책(cycleSeedType) 재등록 테스트 ─────────────────────────────────

    @Test
    void executeBatch_MAINTAIN_holdingsZero_rotatesWithSameDepositAndNotifiesUser() throws InterruptedException {
        BigDecimal initDeposit = new BigDecimal("1000.00");
        Strategy maintainStrategy = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAINTAIN);
        StrategyCycle maintainCycle = new StrategyCycle(
                UUID.randomUUID(), maintainStrategy.id(), initDeposit, null, LocalDate.now(), null, null, null);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(maintainStrategy.id(), 1)).thenReturn(List.of(FRESH_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        // MAINTAIN은 KIS 실잔고 확인 필수 — initDeposit 이상이면 재등록
        when(kisMarginPort.getMargin(ACCOUNT)).thenReturn(List.of(
                new MarginItem(Currency.USD, initDeposit, initDeposit, initDeposit, null)));
        // CycleRotationService.rotate → strategyCyclePort.save 후 id로 CyclePosition 생성
        StrategyCycle savedMaintainCycle = new StrategyCycle(UUID.randomUUID(), maintainStrategy.id(), initDeposit, null, LocalDate.now(), null, null, null);
        when(strategyCyclePort.save(any())).thenReturn(savedMaintainCycle);

        service.executeBatch(List.of(new BatchContext(maintainStrategy, maintainCycle, ACCOUNT, USER)), PAST_DST);

        // 재등록: startAmount 동일 유지 (StrategyCycle로 저장)
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(initDeposit) == 0));
        // 성공 알림 발송
        verify(userNotificationPort).notifyStrategyChanged(eq(USER), eq(ACCOUNT), any(Strategy.class), eq("재등록"));
    }

    @Test
    void executeBatch_MAX_holdingsZero_fetchesMarginAndRotatesAndNotifiesUser() throws InterruptedException {
        BigDecimal marginAmount = new BigDecimal("2000.00");
        // MAX: maxSeed = 마지막 CyclePosition.usdDeposit = FRESH_HISTORY.usdDeposit = 1000
        // actualBalance(2000) >= maxSeed(1000) → targetSeed = 1000
        Strategy maxStrategy = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAX);
        StrategyCycle maxCycle = new StrategyCycle(
                UUID.randomUUID(), maxStrategy.id(), new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);
        BigDecimal expectedSeed = FRESH_HISTORY.usdDeposit(); // 1000.00

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(maxStrategy.id(), 1)).thenReturn(List.of(FRESH_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(kisMarginPort.getMargin(ACCOUNT)).thenReturn(List.of(new MarginItem(Currency.USD, marginAmount, marginAmount, marginAmount, null)));
        // CycleRotationService.rotate → strategyCyclePort.save 후 id로 CyclePosition 생성
        StrategyCycle savedMaxCycle = new StrategyCycle(UUID.randomUUID(), maxStrategy.id(), expectedSeed, null, LocalDate.now(), null, null, null);
        when(strategyCyclePort.save(any())).thenReturn(savedMaxCycle);

        service.executeBatch(List.of(new BatchContext(maxStrategy, maxCycle, ACCOUNT, USER)), PAST_DST);

        // 재등록: 내부 원장(maxSeed=1000)으로 새 StrategyCycle 생성
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(expectedSeed) == 0));
        // 성공 알림 발송
        verify(userNotificationPort).notifyStrategyChanged(eq(USER), eq(ACCOUNT), any(Strategy.class), eq("재등록"));
    }

    @Test
    void executeBatch_MAX_belowMinRequired_skipsRotationAndNotifiesInsufficientBalance() throws InterruptedException {
        // PRICE=22, minRequired = 22 × 40 = 880
        // actualBalance=500, maintainSeed=500 → targetSeed=500 < 880 → notifyInsufficientBalance
        BigDecimal marginAmount = new BigDecimal("500.00");
        Strategy maxStrategy = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAX);
        StrategyCycle maxCycle = new StrategyCycle(
                UUID.randomUUID(), maxStrategy.id(), new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(maxStrategy.id(), 1)).thenReturn(List.of(FRESH_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(kisMarginPort.getMargin(ACCOUNT)).thenReturn(List.of(new MarginItem(Currency.USD, marginAmount, marginAmount, marginAmount, null)));

        service.executeBatch(List.of(new BatchContext(maxStrategy, maxCycle, ACCOUNT, USER)), PAST_DST);

        verify(strategyCyclePort, never()).save(any());
        verify(notifyPort).notifyInsufficientBalance(eq(ACCOUNT), any(AccountBalance.class), eq(Ticker.SOXL));
        verify(userNotificationPort, never()).notifyStrategyChanged(any(), any(), any(), any());
    }

    @Test
    void executeBatch_MAX_marginFails_skipsRotationAndNotifiesError() throws InterruptedException {
        Strategy maxStrategy = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAX);
        StrategyCycle maxCycle = new StrategyCycle(
                UUID.randomUUID(), maxStrategy.id(), new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);
        RuntimeException kisError = new RuntimeException("KIS 증거금 조회 실패");

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(maxStrategy.id(), 1)).thenReturn(List.of(FRESH_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(kisMarginPort.getMargin(ACCOUNT)).thenThrow(kisError);

        service.executeBatch(List.of(new BatchContext(maxStrategy, maxCycle, ACCOUNT, USER)), PAST_DST);

        verify(strategyCyclePort, never()).save(any());
        // rotateCycleIfConsecutive 내부 catch → notifyError (executeBatch 바깥 catch와 별개)
        verify(notifyPort, atLeastOnce()).notifyError(any());
        verify(userNotificationPort, never()).notifyStrategyChanged(any(), any(), any(), any());
    }
}
