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
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.domain.strategy.InfiniteTradingStrategy;
import com.kista.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.domain.strategy.PrivacyTradingStrategy;
import com.kista.domain.strategy.ReverseInfiniteTradingStrategy;
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
    @Mock TosExecutionPort tosExecutionPort;
    @Mock InfiniteTradingStrategy infiniteStrategy;
    @Mock PrivacyTradingStrategy privacyStrategy;
    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort; // TradingService + TradingReporter 양쪽에서 사용
    @Mock OrderPort orderPort;
    @Mock RealtimeNotificationPort realtimeNotificationPort;
    @Mock CyclePositionPort cycleHistoryPort;
    @Mock StrategyPort cyclePort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock KisMarginPort kisMarginPort;
    @Mock TosMarginPort tosMarginPort;
    @Mock KisAccountPort kisAccountPort;
    @Mock TosAccountPort tosAccountPort;
    @Mock LoadUserSettingsPort loadUserSettingsPort;
    TradingService service;

    static final DstInfo PAST_DST = new DstInfo(true,
            Instant.now().minusSeconds(3600),
            Instant.now().minusSeconds(1800),
            Instant.now().minusSeconds(7200)); // marketOpen도 과거로 설정해 대기 skip

    static final BigDecimal PRICE = new BigDecimal("22.00");

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", "01",
            Account.Broker.KIS
    );

    // Strategy + StrategyCycle — 기존 TradingCycle을 두 레이어로 분리
    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE, 20
    );
    static final StrategyCycle STRATEGY_CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED
    );

    // CyclePosition 기반 잔고 (TradingService가 KIS API 대신 이력에서 읽음)
    static final CyclePosition NORMAL_HISTORY = new CyclePosition(
            null, STRATEGY_CYCLE.id(), new BigDecimal("1000.00"), new BigDecimal("22.00"), new BigDecimal("20.00"), 10, false, null, null);
    static final CyclePosition FRESH_HISTORY = new CyclePosition(
            null, STRATEGY_CYCLE.id(), new BigDecimal("1000.00"), null, null, 0, false, null, null);
    static final CyclePosition LOW_HISTORY = new CyclePosition(
            null, STRATEGY_CYCLE.id(), new BigDecimal("10.00"), new BigDecimal("22.00"), new BigDecimal("20.00"), 5, false, null, null);

    static final User USER = new User(
            ACCOUNT.userId(), "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
            null, null, null, null, NotificationChannel.TELEGRAM
    );

    @BeforeEach
    void setUp() {
        // loadUserSettingsPort — TRADING_ALERT 기본값(활성) 반환: 리포트 발송 경로로 진행
        // lenient: 일부 테스트(휴장·placeOpenOrders 등)에서 reporter까지 도달하지 않아 미사용 가능
        lenient().when(loadUserSettingsPort.loadByUserId(any()))
                .thenReturn(Optional.of(UserSettings.defaultFor(USER.id())));

        // 헬퍼 컴포넌트는 실제 인스턴스로 생성 — 기존 mock(cycleHistoryPort, infiniteStrategy 등)이 그대로 동작
        TradingBalanceLoader balanceLoader = new TradingBalanceLoader(cycleHistoryPort);
        TradingOrderPlanner orderPlanner = new TradingOrderPlanner(orderPort);
        ReverseInfiniteTradingStrategy reverseStrategy = mock(ReverseInfiniteTradingStrategy.class);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy),
                new PrivacyCycleOrderStrategy(privacyStrategy)));
        CycleOrderComputer orderComputer = new CycleOrderComputer(cycleStrategies, cycleHistoryPort);
        // CycleRotationService: BrokerMarginRouter가 KIS/Toss 포트를 브로커 기반으로 라우팅
        BrokerMarginRouter marginRouter = new BrokerMarginRouter(kisMarginPort, tosMarginPort);
        CycleRotationService rotationService = new CycleRotationService(
                marginRouter, cyclePort, strategyCyclePort, cycleHistoryPort, notifyPort, userNotificationPort, cycleStrategies, loadUserSettingsPort);
        BrokerPriceRouter priceRouter = new BrokerPriceRouter(kisPricePort, null);
        TradingPriceFetcher priceFetcher = new TradingPriceFetcher(priceRouter);
        BrokerOrderRouter orderRouter = new BrokerOrderRouter(kisOrderPort, null);
        BuyOrderPriceCapper priceCapper = new BuyOrderPriceCapper(orderPort, orderPlanner, infiniteStrategy);
        TradingOrderExecutor orderExecutor = new TradingOrderExecutor(orderPort, orderRouter, priceCapper, notifyPort);
        BrokerExecutionRouter executionRouter = new BrokerExecutionRouter(kisExecutionPort, tosExecutionPort);
        TradingReporter reporter = new TradingReporter(
                executionRouter, orderPort, userNotificationPort, realtimeNotificationPort,
                cycleHistoryPort, strategyCyclePort, rotationService, loadUserSettingsPort);
        BrokerAccountRouter brokerAccountRouter = new BrokerAccountRouter(kisAccountPort, tosAccountPort);
        // KIS 계좌 기준 테스트 — live 잔고 체크 시 kisAccountPort.getBalance() 호출
        // lenient: live 체크에 도달하지 않는 테스트(휴장·기존 주문 존재 등)는 미호출
        lenient().when(kisAccountPort.getBalance(eq(ACCOUNT), any()))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        service = new TradingService(
                marketCalendarPort, notifyPort, userNotificationPort,
                orderPort, privacyTradePort, strategyCyclePort,
                balanceLoader, brokerAccountRouter, orderComputer, orderPlanner,
                priceFetcher, orderExecutor, reporter);
    }

    @Test
    void execute_normalFlow_allPortsCalledInOrder() throws InterruptedException {
        BigDecimal startPrice = new BigDecimal("20.00"); // 시작가 (Phase A, 04:00 KST)
        BigDecimal prevClose = new BigDecimal("19.00");  // 전일종가
        // PRICE = "22.00" — 종가 (PostClose 이후)

        Order template = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, startPrice, Order.OrderStatus.PLANNED, null, null, null);
        UUID plannedId = UUID.randomUUID();
        Order planned = new Order(plannedId, ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, startPrice,
                Order.OrderStatus.PLANNED, null, null, null);
        Order placedOrder = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, startPrice, Order.OrderStatus.PLACED, "ORD-001", null, null);

        when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(startPrice, prevClose))); // 시작가+전일종가
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, PRICE)); // 종가
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(template));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any(LocalDate.class)))
                .thenReturn(List.of()); // 오늘 주문 없음 → 신규 계산
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any(LocalDate.class)))
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
        verify(orderPort, atLeastOnce()).findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any());
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
        Order alreadyPlanned = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLANNED, null, null, null);
        Order placedOrder = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, "ORD-001", null, null);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        // 오늘 이미 PLANNED 주문 존재 → planAndSaveOrders에서 재계산 skip
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(alreadyPlanned));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(alreadyPlanned));
        when(orderPort.findPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of()); // 장 개시 스케쥴러 선접수 없음
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
    void execute_withBuyExecution_savesPostTradeBalanceAndNotifiesPostTradeSnapshot() throws InterruptedException {
        // 0회차(holdings=0) → 1주 매수 체결 → 이력: holdings=1, avgPrice=체결가 / 알림: 보유 1주
        BigDecimal startPrice = new BigDecimal("20.00");
        BigDecimal closingPrice = new BigDecimal("22.00"); // PRICE
        BigDecimal executionPrice = new BigDecimal("20.50"); // LOC 체결가 (개장가~종가 사이)
        BigDecimal executionAmount = new BigDecimal("20.50"); // 1주 × $20.50

        Order template = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, startPrice, Order.OrderStatus.PLANNED, null, null, null);
        UUID plannedId = UUID.randomUUID();
        Order planned = new Order(plannedId, ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, startPrice,
                Order.OrderStatus.PLANNED, null, null, null);
        Order placedOrder = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, startPrice, Order.OrderStatus.PLACED, "ORD-001", null, null);
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
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any(LocalDate.class)))
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
        verify(userNotificationPort).notifyTradingReport(eq(USER), eq(ACCOUNT), any(TradingReport.class));
    }

    // ── placeOpenOrders 테스트 ────────────────────────────────────────────────

    @Test
    void placeOpenOrders_savesAllOrdersAndPlacesSellsOnly() throws InterruptedException {
        // AT_OPEN SELL 주문은 개장 시 선접수, BUY는 AT_CLOSE 마감 배치
        BigDecimal prevClose = new BigDecimal("19.00");
        Order buyTemplate  = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,  1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED, null, null, null);
        Order sellTemplate = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"), Order.OrderStatus.PLANNED, null, null, null);

        UUID sellPlannedId = UUID.randomUUID();
        Order sellPlanned = new Order(sellPlannedId, ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
                Order.OrderStatus.PLANNED, null, null, null);
        Order sellPlacedKis = new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
                Order.OrderStatus.PLACED, "ORD-SELL-001", null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, prevClose)));
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate, sellTemplate));
        // 저장 후 SELL PLANNED 조회
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(sellPlanned)); // AT_OPEN SELL만 반환 — placement 필터로 선접수 대상 결정
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(sellPlacedKis);

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 전체 주문 저장 (BUY + SELL)
        verify(orderPort).saveAll(anyList());
        // SELL만 KIS 접수
        verify(kisOrderPort).place(eq(sellPlanned), eq(ACCOUNT));
        verify(orderPort).markPlaced(eq(sellPlannedId), eq("ORD-SELL-001"));
        // 잔고 충분하므로 사용자 알람 없음
        verify(userNotificationPort, never()).notifyInsufficientBalance(any(), any(), any(), any());
    }

    @Test
    void placeOpenOrders_insufficientBalance_notifiesUserAndSkipsSave() throws InterruptedException {
        // 매수 금액 초과 → live 잔고 부족 → 사용자 알람, 저장 건너뜀
        BigDecimal prevClose = new BigDecimal("19.00");
        Order bigBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 100, new BigDecimal("500.00"), // 50,000 >> usdDeposit=10
                Order.OrderStatus.PLANNED, null, null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, prevClose)));
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(LOW_HISTORY)); // usdDeposit=10
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(bigBuy));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        // live 잔고 부족: BUY $50,000 > usdDeposit $10
        when(kisAccountPort.getBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("10.00")));

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 예수금 부족 — 사용자 알람 발송
        verify(userNotificationPort).notifyInsufficientBalance(eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
        // 저장 건너뜀
        verify(orderPort, never()).saveAll(any());
        // KIS 접수 없음
        verify(kisOrderPort, never()).place(any(), any());
    }

    @Test
    void placeOpenOrders_insufficientHoldings_notifiesUserAndSkipsSave() throws InterruptedException {
        BigDecimal prevClose = new BigDecimal("19.00");
        // SELL 100주 — live holdings=5주 → 보유수량 부족
        Order bigSell = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 100, new BigDecimal("22.00"),
                Order.OrderStatus.PLANNED, null, null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, prevClose)));
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(bigSell));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        // live holdings=5 < SELL 100주 → 보유수량 부족
        when(kisAccountPort.getBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(5, new BigDecimal("20.00"), new BigDecimal("10000.00")));

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(userNotificationPort).notifyInsufficientBalance(eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
        verify(orderPort, never()).saveAll(any());
    }

    @Test
    void placeOpenOrders_noSellOrders_skipsKisPlace() throws InterruptedException {
        // 후반 최종회차 등 SELL 없음 — KIS 접수 0건 (정상)
        BigDecimal prevClose = new BigDecimal("19.00");
        Order buyTemplate = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED, null, null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, prevClose)));
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of()); // AT_OPEN 주문 없음

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort).saveAll(anyList());
        verify(kisOrderPort, never()).place(any(), any());
    }

    @Test
    void executeBatch_liveBalanceInsufficient_skipsOrderPlanAndNotifies() throws InterruptedException {
        // 마감 스케쥴러 plan 단계 — live 잔고 부족 시 PLANNED 저장 건너뜀
        BigDecimal prevClose = new BigDecimal("19.00");
        Order bigBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 100, new BigDecimal("500.00"),
                Order.OrderStatus.PLANNED, null, null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, prevClose)));
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(bigBuy));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        // live 잔고 부족: BUY $50,000 > usdDeposit $10
        when(kisAccountPort.getBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("10.00")));

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 알림 발송
        verify(userNotificationPort).notifyInsufficientBalance(eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
        // 저장 없음
        verify(orderPort, never()).saveAll(any());
        // 브로커 접수 없음
        verify(kisOrderPort, never()).place(any(), any());
    }

    @Test
    void executeBatch_skipBranch_recomputesPositionForBuyCapping() throws InterruptedException {
        // 장 개시 스케쥴러 먼저 실행 → 오늘 주문 존재 → skip 분기 → position 재계산 → BUY 접수
        Order existingSell = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
                Order.OrderStatus.PLACED, "ORD-SELL-001", null, null);
        Order existingBuy = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, PRICE,
                Order.OrderStatus.PLANNED, null, null, null);
        Order placedBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, "ORD-BUY-001", null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        // 오늘 주문 이미 존재 (SELL은 PLACED, BUY는 PLANNED)
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingSell, existingBuy));
        // BUY 잔고 유효 ($22 < $1000)
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingBuy)); // PLANNED BUY만
        // PLACED SELL 조회 (placeAll에서 prePlacedSells)
        when(orderPort.findPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingSell));
        when(infiniteStrategy.buildOrders(any(), any())).thenReturn(List.of()); // 재계산용 mock
        when(kisOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedBuy);
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 재계산은 했지만 saveAll은 없음
        verify(orderPort, never()).saveAll(any());
        // BUY 접수
        verify(kisOrderPort).place(any(), eq(ACCOUNT));
        // 잔고 충분 — 사용자 알람 없음
        verify(userNotificationPort, never()).notifyInsufficientBalance(any(), any(), any(), any());
    }

    // ── executeBatch 테스트 ────────────────────────────────────────────────────

    @Test
    void executeBatch_fetchesPricesTwice_startAndClose_notPerCycle() throws InterruptedException {
        // 두 전략이 같은 ticker → getPriceSnapshots() 1회(시작가), getPrices() 1회(종가), 단건 fallback 없음
        Strategy strategy2 = new Strategy(UUID.randomUUID(), ACCOUNT.id(),
                Strategy.Type.INFINITE, Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE, 20);
        StrategyCycle cycle2 = new StrategyCycle(UUID.randomUUID(), strategy2.id(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED);
        CyclePosition history2 = new CyclePosition(
                null, cycle2.id(), new BigDecimal("1000.00"), new BigDecimal("20.00"), new BigDecimal("20.00"), 10, false, null, null);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
        when(cycleHistoryPort.findLatestByStrategyId(strategy2.id(), 1)).thenReturn(List.of(history2));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByCycleAndDate(any(), any())).thenReturn(List.of());
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
                Strategy.Type.INFINITE, Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE, 20);
        StrategyCycle cycle2 = new StrategyCycle(UUID.randomUUID(), strategy2.id(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED);
        CyclePosition history2 = new CyclePosition(
                null, cycle2.id(), new BigDecimal("1000.00"), new BigDecimal("20.00"), new BigDecimal("20.00"), 10, false, null, null);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE), Ticker.TQQQ, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE, Ticker.TQQQ, PRICE));
        // STRATEGY: 잔고 조회에서 예외
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        RuntimeException ex = new RuntimeException("잔고 조회 오류");
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenThrow(ex);
        when(cycleHistoryPort.findLatestByStrategyId(strategy2.id(), 1)).thenReturn(List.of(history2));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByCycleAndDate(any(), any())).thenReturn(List.of());
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
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAINTAIN, 20);
        StrategyCycle maintainCycle = new StrategyCycle(
                UUID.randomUUID(), maintainStrategy.id(), initDeposit, null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(maintainStrategy.id(), 1)).thenReturn(List.of(FRESH_HISTORY));
        // 사이클 종료 판정: 이전 포지션 holdings > 0 → 진짜 청산으로 판단 (limit 무관, CycleOrderComputer=2, Reporter=1)
        when(cycleHistoryPort.findLatestByCycleId(eq(maintainCycle.id()), anyInt())).thenReturn(List.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByCycleAndDate(eq(maintainCycle.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        // MAINTAIN은 KIS 실잔고 확인 필수 — initDeposit 이상이면 재등록
        when(kisMarginPort.getUsdBuyableAmount(ACCOUNT)).thenReturn(initDeposit);
        // CycleRotationService.rotate → strategyCyclePort.save 후 id로 CyclePosition 생성
        StrategyCycle savedMaintainCycle = new StrategyCycle(UUID.randomUUID(), maintainStrategy.id(), initDeposit, null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED);
        when(strategyCyclePort.save(any())).thenReturn(savedMaintainCycle);

        service.executeBatch(List.of(new BatchContext(maintainStrategy, maintainCycle, ACCOUNT, USER)), PAST_DST);

        // 재등록: startAmount 동일 유지 (StrategyCycle로 저장)
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(initDeposit) == 0));
        // 성공 알림 발송
    }

    @Test
    void executeBatch_MAX_holdingsZero_fetchesMarginAndRotatesAndNotifiesUser() throws InterruptedException {
        BigDecimal marginAmount = new BigDecimal("2000.00");
        // MAX: maxSeed = 마지막 CyclePosition.usdDeposit = FRESH_HISTORY.usdDeposit = 1000
        // actualBalance(2000) >= maxSeed(1000) → targetSeed = 1000
        Strategy maxStrategy = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAX, 20);
        StrategyCycle maxCycle = new StrategyCycle(
                UUID.randomUUID(), maxStrategy.id(), new BigDecimal("500.00"), null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED);
        BigDecimal expectedSeed = FRESH_HISTORY.usdDeposit(); // 1000.00

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(maxStrategy.id(), 1)).thenReturn(List.of(FRESH_HISTORY));
        // 사이클 종료 판정: 이전 포지션 holdings > 0 → 진짜 청산으로 판단 (limit 무관, CycleOrderComputer=2, Reporter=1)
        when(cycleHistoryPort.findLatestByCycleId(eq(maxCycle.id()), anyInt())).thenReturn(List.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(kisMarginPort.getUsdBuyableAmount(ACCOUNT)).thenReturn(marginAmount);
        // CycleRotationService.rotate → strategyCyclePort.save 후 id로 CyclePosition 생성
        StrategyCycle savedMaxCycle = new StrategyCycle(UUID.randomUUID(), maxStrategy.id(), expectedSeed, null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED);
        when(strategyCyclePort.save(any())).thenReturn(savedMaxCycle);

        service.executeBatch(List.of(new BatchContext(maxStrategy, maxCycle, ACCOUNT, USER)), PAST_DST);

        // 재등록: 내부 원장(maxSeed=1000)으로 새 StrategyCycle 생성
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(expectedSeed) == 0));
        // 성공 알림 발송
    }

    @Test
    void executeBatch_MAX_belowMinRequired_skipsRotationAndNotifiesInsufficientBalance() throws InterruptedException {
        // PRICE=22, minRequired = 22 × (20 × 2.2) = 968
        // actualBalance=500, maintainSeed=500 → targetSeed=500 < 968 → notifyInsufficientBalance
        BigDecimal marginAmount = new BigDecimal("500.00");
        Strategy maxStrategy = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAX, 20);
        StrategyCycle maxCycle = new StrategyCycle(
                UUID.randomUUID(), maxStrategy.id(), new BigDecimal("500.00"), null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(maxStrategy.id(), 1)).thenReturn(List.of(FRESH_HISTORY));
        // 사이클 종료 판정: 이전 포지션 holdings > 0 → 진짜 청산으로 판단 (limit 무관, CycleOrderComputer=2, Reporter=1)
        when(cycleHistoryPort.findLatestByCycleId(eq(maxCycle.id()), anyInt())).thenReturn(List.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(kisMarginPort.getUsdBuyableAmount(ACCOUNT)).thenReturn(marginAmount);

        service.executeBatch(List.of(new BatchContext(maxStrategy, maxCycle, ACCOUNT, USER)), PAST_DST);

        verify(strategyCyclePort, never()).save(any());
        verify(notifyPort).notifyInsufficientBalance(eq(ACCOUNT), any(AccountBalance.class), eq(Ticker.SOXL));
    }

    @Test
    void executeBatch_firstDayBuyFails_doesNotEndCycle() throws InterruptedException {
        // 0회차(holdings=0) 매수 실패 — 이전 포지션이 startSnapshot(holdings=0)이므로 사이클 종료가 아님
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(FRESH_HISTORY)); // holdings=0
        // 이전 포지션도 holdings=0 (startSnapshot) — 진짜 청산 아님
        when(cycleHistoryPort.findLatestByCycleId(eq(STRATEGY_CYCLE.id()), anyInt())).thenReturn(List.of(FRESH_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of()); // 체결 없음

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 0회차 매수 실패 → 사이클 종료 처리 금지
        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(userNotificationPort, never()).notifyCycleCompleted(any(), any(), any());
        verify(strategyCyclePort, never()).save(any()); // 새 사이클 재등록도 없음
    }

    @Test
    void executeBatch_MAX_marginFails_skipsRotationAndNotifiesError() throws InterruptedException {
        Strategy maxStrategy = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAX, 20);
        StrategyCycle maxCycle = new StrategyCycle(
                UUID.randomUUID(), maxStrategy.id(), new BigDecimal("500.00"), null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED);
        RuntimeException kisError = new RuntimeException("KIS 증거금 조회 실패");

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestByStrategyId(maxStrategy.id(), 1)).thenReturn(List.of(FRESH_HISTORY));
        // 사이클 종료 판정: 이전 포지션 holdings > 0 → 진짜 청산으로 판단 (limit 무관, CycleOrderComputer=2, Reporter=1)
        when(cycleHistoryPort.findLatestByCycleId(eq(maxCycle.id()), anyInt())).thenReturn(List.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of());
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(kisMarginPort.getUsdBuyableAmount(ACCOUNT)).thenThrow(kisError);

        service.executeBatch(List.of(new BatchContext(maxStrategy, maxCycle, ACCOUNT, USER)), PAST_DST);

        verify(strategyCyclePort, never()).save(any());
        // rotateCycleIfConsecutive 내부 catch → notifyError (executeBatch 바깥 catch와 별개)
        verify(notifyPort, atLeastOnce()).notifyError(any());
    }
}
