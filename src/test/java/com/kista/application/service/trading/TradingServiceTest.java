package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.broker.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.BrokerOrderCorrectionPort;
import com.kista.domain.port.out.broker.BrokerPricePort;
import com.kista.domain.port.out.broker.ExecutionPort;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.port.out.broker.MarginPort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import com.kista.domain.port.out.StrategyCycleVrPort;
import com.kista.domain.port.out.StrategyVrDetailPort;
import com.kista.domain.strategy.*;
import com.kista.support.DomainFixtures;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingServiceTest {

    @Mock MarketCalendarPort marketCalendarPort;
    @Mock BrokerPricePort kisPricePort;  // BrokerPricePort 직접 mock (KisPricePort 삭제됨)
    @Mock BrokerOrderCorrectionPort brokerOrderPort;
    @Mock ExecutionPort kisExecutionPort; // ExecutionPort 직접 mock (KisExecutionPort 삭제됨)
    @Mock InfiniteStrategy infiniteStrategy;
    @Mock ReverseInfiniteStrategy reverseStrategy;
    @Mock PrivacyStrategy privacyStrategy;
    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort; // TradingService + TradingReporter 양쪽에서 사용
    @Mock OrderPort orderPort;
    @Mock RealtimeNotificationPort realtimeNotificationPort;
    @Mock CyclePositionPort cycleHistoryPort;
    @Mock CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    @Mock StrategyPort cyclePort;
    @Mock StrategyVersionPort strategyVersionPort;
    @Mock StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CycleSnapshotCreator cycleSnapshotCreator; // CycleRotationService: StrategyCycle+CyclePosition 원자 저장
    @Mock PrivacyTradePort privacyTradePort;
    @Mock com.kista.domain.port.out.broker.MarginPort kisMarginBrokerPort; // BrokerAdapterRegistry → CycleRotationService 위임용
    @Mock LiveBalancePort liveBalancePort;
    @Mock SellableQuantityPort sellableQuantityPort;
    @Mock UserSettingsPort userSettingsPort;
    @Mock UserPort userPort;
    @Mock StrategyCycleVrPort strategyCycleVrPort; // VR 사이클 상세 조회 (CycleOrderComputer용)
    @Mock StrategyVrDetailPort strategyVrDetailPort; // VR 전략 버전 상세 조회 (CycleOrderComputer용)
    @Mock VrStrategy vrStrategy; // VR 전략 주문 생성 mock (VrCycleOrderStrategy 조립용)
    TradingService service;

    static final DstInfo PAST_DST = new DstInfo(true,
            Instant.now().minusSeconds(3600),
            Instant.now().minusSeconds(1800),
            Instant.now().minusSeconds(7200)); // marketOpen도 과거로 설정해 대기 skip

    static final BigDecimal PRICE = new BigDecimal("22.00");

    static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());

    // Strategy + StrategyCycle — 기존 TradingCycle을 두 레이어로 분리
    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE
    );
    static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();
    static final StrategyCycle STRATEGY_CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), STRATEGY_VERSION_ID, new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null
    );

    // CyclePosition 기반 잔고 (TradingService가 KIS API 대신 이력에서 읽음)
    static final CyclePosition NORMAL_HISTORY = new CyclePosition(
            null, STRATEGY_CYCLE.id(), new BigDecimal("1000.00"), new BigDecimal("22.00"), new BigDecimal("20.00"), 10, null, null);
    static final CyclePosition FRESH_HISTORY = new CyclePosition(
            null, STRATEGY_CYCLE.id(), new BigDecimal("1000.00"), null, null, 0, null, null);
    static final CyclePosition LOW_HISTORY = new CyclePosition(
            null, STRATEGY_CYCLE.id(), new BigDecimal("10.00"), new BigDecimal("22.00"), new BigDecimal("20.00"), 5, null, null);

    static final User USER = DomainFixtures.activeUserWithTelegram(ACCOUNT.userId());

    @BeforeEach
    void setUp() {
        // userSettingsPort — TRADING_ALERT 기본값(활성) 반환: 리포트 발송 경로로 진행
        // lenient: 일부 테스트(휴장·placeOpenOrders 등)에서 reporter까지 도달하지 않아 미사용 가능
        lenient().when(userSettingsPort.findOrDefault(any()))
                .thenReturn(UserSettings.defaultFor(USER.id()));

        // 헬퍼 컴포넌트는 실제 인스턴스로 생성 — 기존 mock(cycleHistoryPort, infiniteStrategy 등)이 그대로 동작
        TradingBalanceLoader balanceLoader = new TradingBalanceLoader(cycleHistoryPort);
        TradingOrderPlanner orderPlanner = new TradingOrderPlanner(orderPort);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy),
                new PrivacyCycleOrderStrategy(privacyStrategy),
                new VrCycleOrderStrategy(vrStrategy))); // VR 전략 추가 — VR executeBatch 테스트용
        CycleOrderComputer orderComputer = new CycleOrderComputer(
                cycleStrategies, cycleHistoryPort, cyclePositionInfiniteDetailPort, strategyInfiniteDetailPort,
                strategyCyclePort, strategyCycleVrPort, strategyVrDetailPort, orderPort, new TradingDayCounter(marketCalendarPort));
        // CycleRotationService: BrokerAdapterRegistry.require(account, MarginPort) → kisMarginPort로 위임
        BrokerAdapterRegistry marginRegistry = mock(BrokerAdapterRegistry.class);
        lenient().when(marginRegistry.require(any(Account.class),
                eq(MarginPort.class))).thenReturn(kisMarginBrokerPort);
        CycleRotationService rotationService = new CycleRotationService(
                marginRegistry, cyclePort, strategyVersionPort, strategyInfiniteDetailPort,
                cycleHistoryPort, cycleSnapshotCreator, notifyPort, userNotificationPort, cycleStrategies, userSettingsPort);
        // 브로커 포트 레지스트리 — 각 mock을 직접 연결 (KisPricePort/KisExecutionPort 삭제로 단순화)
        BrokerAdapterRegistry tradingRegistry = mock(BrokerAdapterRegistry.class);

        // BrokerPricePort: kisPricePort 직접 연결 (위임 레이어 제거)
        lenient().doReturn(kisPricePort).when(tradingRegistry).require(any(Account.class), eq(BrokerPricePort.class));

        // BrokerOrderCorrectionPort: 필드 mock 직접 연결
        lenient().doReturn(brokerOrderPort).when(tradingRegistry).require(any(Account.class), eq(BrokerOrderCorrectionPort.class));

        // ExecutionPort: kisExecutionPort 직접 연결 (위임 레이어 제거)
        lenient().doReturn(kisExecutionPort).when(tradingRegistry).require(any(Account.class), eq(ExecutionPort.class));

        // LiveBalancePort: 필드 mock 직접 연결
        lenient().doReturn(liveBalancePort).when(tradingRegistry).require(any(Account.class), eq(LiveBalancePort.class));

        // SellableQuantityPort: BUY 예산과 독립적인 SELL 판매가능수량 검증
        lenient().doReturn(sellableQuantityPort).when(tradingRegistry).require(any(Account.class), eq(SellableQuantityPort.class));

        BuyOrderPriceCapper priceCapper = new BuyOrderPriceCapper(orderPort, orderPlanner, infiniteStrategy);
        TradingPriceFetcher priceFetcher = new TradingPriceFetcher(tradingRegistry);
        TradingOrderExecutor orderExecutor = new TradingOrderExecutor(orderPort, tradingRegistry, priceCapper, notifyPort, cycleStrategies);
        // CyclePositionPersistor: 포지션 스냅샷 저장 책임 분리 (TradingReporter에서 추출)
        VrCycleRolloverService vrRolloverService = mock(VrCycleRolloverService.class); // VR 롤오버 mock
        CyclePositionPersistor positionPersistor = new CyclePositionPersistor(
                cycleHistoryPort, cyclePositionInfiniteDetailPort, strategyInfiniteDetailPort,
                strategyCyclePort, rotationService, userNotificationPort, cycleStrategies, vrRolloverService);
        TradingReporter reporter = new TradingReporter(
                tradingRegistry, orderPort, userNotificationPort, realtimeNotificationPort,
                userSettingsPort, positionPersistor, notifyPort);
        // 계좌 기준 테스트 — live 잔고 체크 시 liveBalancePort.getLiveBalance() 호출
        // lenient: live 체크에 도달하지 않는 테스트(휴장·기존 주문 존재 등)는 미호출
        lenient().when(liveBalancePort.getLiveBalance(eq(ACCOUNT), any()))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        lenient().when(sellableQuantityPort.getSellableQuantity(any(), any()))
                .thenReturn(new SellableQuantity("SOXL", 100));
        lenient().when(orderPort.sumPlannedBuyByAccountAndDate(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(infiniteStrategy.buildCappedBuyOrders(any(), any(), anyList(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        lenient().when(cyclePositionInfiniteDetailPort.findLatestByCycleId(any(), anyInt())).thenReturn(List.of());
        lenient().when(cyclePositionInfiniteDetailPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(cycleHistoryPort.save(any()))
                .thenAnswer(invocation -> {
                    CyclePosition position = invocation.getArgument(0);
                    return new CyclePosition(
                            position.id() != null ? position.id() : UUID.randomUUID(),
                            position.strategyCycleId(),
                            position.usdDeposit(),
                            position.closingPrice(),
                            position.avgPrice(),
                            position.holdings(),
                            position.createdAt(),
                            position.deletedAt());
                });
        lenient().when(strategyVersionPort.findActiveByStrategyId(any()))
                .thenAnswer(invocation -> Optional.of(new StrategyVersion(STRATEGY_VERSION_ID, invocation.getArgument(0), 1, null, null)));
        lenient().when(strategyInfiniteDetailPort.findActiveByStrategyId(any()))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 20)));
        lenient().when(strategyInfiniteDetailPort.findByStrategyVersionId(any()))
                .thenAnswer(invocation -> Optional.of(new StrategyInfiniteDetail(invocation.getArgument(0), 20)));
        // MarketEventNotifier — UserPort/UserSettingsPort/UserNotificationPort를 직접 주입해 생성
        MarketEventNotifier marketEventNotifier = new MarketEventNotifier(userPort, userSettingsPort, userNotificationPort);
        TradingOrderBudgetAllocator budgetAllocator = new TradingOrderBudgetAllocator(
                tradingRegistry, orderPort, cycleStrategies);
        service = new TradingService(
                marketCalendarPort, notifyPort, userNotificationPort,
                orderPort, privacyTradePort, strategyCyclePort,
                balanceLoader, orderComputer, orderPlanner,
                priceFetcher, orderExecutor, reporter,
                marketEventNotifier, budgetAllocator, priceCapper, cycleStrategies);
    }

    @Test
    void execute_normalFlow_allPortsCalledInOrder() throws InterruptedException {
        BigDecimal startPrice = new BigDecimal("20.00"); // 시작가 (Phase A, 04:00 KST)
        BigDecimal prevClose = new BigDecimal("19.00");  // 전일종가
        // PRICE = "22.00" — 종가 (PostClose 이후)

        Order template = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, startPrice, Order.OrderStatus.PLANNED, null, null, null)
                .withLeg("TEST_NORMAL_BUY");
        UUID plannedId = UUID.randomUUID();
        Order planned = new Order(plannedId, ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, startPrice,
                Order.OrderStatus.PLANNED, null, null, null);
        Order placedOrder = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, startPrice, Order.OrderStatus.PLACED, "ORD-001", null, null);

        when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(startPrice, prevClose))); // 시작가+전일종가
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, PRICE)); // 종가
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(template));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any(LocalDate.class)))
                .thenReturn(List.of()); // 오늘 주문 없음 → 신규 계산
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(brokerOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedOrder);
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.execute(STRATEGY, ACCOUNT, USER, PAST_DST);

        verify(marketCalendarPort).isMarketOpen(any());
        verify(cycleHistoryPort).findLatestOneByStrategyId(STRATEGY.id());
        verify(kisPricePort, never()).getPriceSnapshot(any(), any()); // 단건 fallback 없음 — getPriceSnapshots 성공
        verify(kisPricePort, never()).getPrice(any(), any());         // 단건 fallback 없음
        verify(kisPricePort).getPriceSnapshots(anyList(), eq(ACCOUNT)); // 시작가(Phase A) 1회
        verify(kisPricePort).getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT));         // 종가(PostClose) 1회
        verify(orderPort).saveAll(anyList());
        verify(orderPort, atLeastOnce()).findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any());
        verify(brokerOrderPort).place(any(), eq(ACCOUNT));
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
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        // 오늘 이미 PLANNED 주문 존재 → planAndSaveOrders에서 재계산 skip
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(alreadyPlanned));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(alreadyPlanned));
        when(orderPort.findPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of()); // 장 개시 스케쥴러 선접수 없음
        when(brokerOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedOrder);
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 재계산 없음 — saveAll 미호출
        verify(orderPort, never()).saveAll(any());
        // KIS 접수는 정상 수행
        verify(brokerOrderPort).place(any(), eq(ACCOUNT));
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
        verify(cycleHistoryPort, never()).findLatestOneByStrategyId(any());
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
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, startPrice, Order.OrderStatus.PLANNED, null, null, null)
                .withLeg("TEST_FRESH_BUY");
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
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, closingPrice)); // 종가
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(FRESH_HISTORY)); // holdings=0
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(template));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any(LocalDate.class)))
                .thenReturn(List.of(planned));
        when(brokerOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedOrder);
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
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,  1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED, null, null, null)
                .withLeg("TEST_CLOSE_BUY");
        Order sellTemplate = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"), Order.OrderStatus.PLANNED, null, null, null)
                .withLeg("TEST_OPEN_SELL");

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
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate, sellTemplate));
        // 저장 후 AT_OPEN PLANNED 조회
        when(orderPort.findAtOpenPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(sellPlanned)); // AT_OPEN SELL만 반환 — placement 필터로 선접수 대상 결정
        when(brokerOrderPort.place(any(), eq(ACCOUNT))).thenReturn(sellPlacedKis);

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 전체 주문 저장 (BUY + SELL)
        verify(orderPort).saveAll(anyList());
        // SELL만 KIS 접수
        verify(brokerOrderPort).place(eq(sellPlanned), eq(ACCOUNT));
        verify(orderPort).markPlaced(eq(sellPlannedId), eq("ORD-SELL-001"));
        // 잔고 충분하므로 사용자 알람 없음
        verify(userNotificationPort, never()).notifyInsufficientBalance(any(), any(), any(), any());
    }

    @Test
    void placeOpenOrders_existingAtCloseBuy_doesNotBlockMissingAtOpenSell() throws InterruptedException {
        // 기존 AT_CLOSE BUY는 반대 timing/direction 슬롯인 AT_OPEN SELL 생성을 막지 않는다.
        UUID sellPlannedId = UUID.randomUUID();
        Order existingBuy = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("20.00"),
                Order.OrderStatus.PLANNED, null, null, null);
        Order sellTemplate = new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_OPEN_SELL");
        Order sellPlanned = new Order(sellPlannedId, ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
                Order.OrderStatus.PLANNED, null, null, null);
        Order sellPlacedKis = new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
                Order.OrderStatus.PLACED, "ORD-SELL-002", null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(sellTemplate));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingBuy));
        when(orderPort.findAtOpenPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(sellPlanned));
        when(brokerOrderPort.place(any(), eq(ACCOUNT))).thenReturn(sellPlacedKis);

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort).saveAll(argThat(saved -> saved.size() == 1
                && saved.getFirst().timing() == Order.OrderTiming.AT_OPEN
                && saved.getFirst().direction() == Order.OrderDirection.SELL));
        verify(brokerOrderPort).place(eq(sellPlanned), eq(ACCOUNT));
        verify(orderPort).markPlaced(eq(sellPlannedId), eq("ORD-SELL-002"));
    }

    @Test
    void placeOpenOrders_insufficientBalance_notifiesUserAndSkipsSave() throws InterruptedException {
        // 매수 금액 초과 → live 잔고 부족 → 사용자 알람, 저장 건너뜀
        BigDecimal prevClose = new BigDecimal("19.00");
        Order bigBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 100, new BigDecimal("500.00"), // 50,000 >> usdDeposit=10
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_BIG_BUY");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(new BigDecimal("500.00"), prevClose)));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(LOW_HISTORY)); // usdDeposit=10
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(bigBuy));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        // live 잔고 부족: BUY $50,000 > usdDeposit $10
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("10.00")));

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 예수금 부족 — 사용자 알람 발송
        verify(userNotificationPort).notifyInsufficientBalance(eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
        // 저장 건너뜀
        verify(orderPort, never()).saveAll(any());
        // AT_OPEN 접수 단계 자체도 진입하지 않음
        verify(orderPort, never()).findAtOpenPlannedByCycleAndDate(any(), any());
        verify(brokerOrderPort, never()).place(any(), any());
    }

    @Test
    void placeOpenOrders_insufficientHoldings_notifiesUserAndSkipsSave() throws InterruptedException {
        BigDecimal prevClose = new BigDecimal("19.00");
        // SELL 100주 — 증권사 판매가능수량=5주 → 판매가능수량 부족
        Order bigSell = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 100, new BigDecimal("22.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_BIG_SELL");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, prevClose)));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(bigSell));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        when(sellableQuantityPort.getSellableQuantity(eq(Ticker.SOXL), eq(ACCOUNT)))
                .thenReturn(new SellableQuantity("SOXL", 5));

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(userNotificationPort).notifyInsufficientBalance(eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
        verify(orderPort, never()).saveAll(any());
        verify(orderPort, never()).findAtOpenPlannedByCycleAndDate(any(), any());
        verify(brokerOrderPort, never()).place(any(), any());
    }

    @Test
    void placeOpenOrders_saveFailureWithoutExistingOrders_skipsAtOpenPlacement() throws InterruptedException {
        RuntimeException saveFailure = new RuntimeException("save failed");
        Order sell = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_OPEN_SELL");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(sell));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        doThrow(saveFailure).when(orderPort).saveAll(anyList());

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(notifyPort).notifyError(saveFailure);
        verify(orderPort, never()).findAtOpenPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any());
        verify(brokerOrderPort, never()).place(any(), any());
    }

    @Test
    void placeOpenOrders_sellRejected_stillSavesApprovedBuy() throws InterruptedException {
        Order buy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("20.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_CLOSE_BUY");
        Order sell = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_OPEN_SELL");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buy, sell));
        when(sellableQuantityPort.getSellableQuantity(eq(Ticker.SOXL), eq(ACCOUNT)))
                .thenReturn(new SellableQuantity("SOXL", 0));

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort).saveAll(argThat(saved -> saved.size() == 1
                && saved.getFirst().direction() == Order.OrderDirection.BUY));
        verify(userNotificationPort).notifyInsufficientBalance(
                eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
    }

    @Test
    void placeOpenOrders_cappedBuyExceedsBudget_savesOnlyApprovedSell() throws InterruptedException {
        Order originalBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("60.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_ORIGINAL_BUY");
        Order sell = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("45.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_OPEN_SELL");
        Order cappedBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 2, new BigDecimal("55.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_CAPPED_BUY");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(new BigDecimal("50.00"), new BigDecimal("49.00"))));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(originalBuy, sell));
        when(infiniteStrategy.buildCappedBuyOrders(any(InfinitePosition.class), any(LocalDate.class),
                eq(List.of(originalBuy)), eq(new BigDecimal("55.00"))))
                .thenReturn(List.of(cappedBuy));
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("80.00")));
        when(sellableQuantityPort.getSellableQuantity(eq(Ticker.SOXL), eq(ACCOUNT)))
                .thenReturn(new SellableQuantity("SOXL", 1));

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort).saveAll(argThat(saved -> saved.size() == 1
                && saved.getFirst().direction() == Order.OrderDirection.SELL));
        verify(userNotificationPort).notifyInsufficientBalance(
                eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
    }

    @Test
    void placeOpenOrders_allocatesBuyBudgetByStrategyPriorityPerAccount() throws InterruptedException {
        Strategy vr = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        Strategy infinite = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        Strategy privacy = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.PRIVACY,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vr.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        StrategyCycle infiniteCycle = new StrategyCycle(UUID.randomUUID(), infinite.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        StrategyCycle privacyCycle = new StrategyCycle(UUID.randomUUID(), privacy.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        CyclePosition vrHistory = new CyclePosition(null, vrCycle.id(), new BigDecimal("1000.00"),
                PRICE, new BigDecimal("20.00"), 10, null, null);
        CyclePosition infiniteHistory = new CyclePosition(null, infiniteCycle.id(), new BigDecimal("1000.00"),
                PRICE, new BigDecimal("20.00"), 10, null, null);
        CyclePosition privacyHistory = new CyclePosition(null, privacyCycle.id(), new BigDecimal("1000.00"),
                PRICE, new BigDecimal("20.00"), 10, null, null);
        PrivacyTradeBase privacyBase = new PrivacyTradeBase(
                UUID.randomUUID(), new BigDecimal("20.00"), 10, new BigDecimal("20.00"), List.of());
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                vrCycle.id(), new BigDecimal("1000.00"), 10, new BigDecimal("2500.00"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(
                vrCycle.strategyVersionId(), 4, new BigDecimal("15.00"), 0);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(
                Ticker.SOXL, new PriceSnapshot(new BigDecimal("1000.00"), new BigDecimal("19.00")),
                Ticker.TQQQ, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(cycleHistoryPort.findLatestOneByStrategyId(vr.id())).thenReturn(Optional.of(vrHistory));
        when(cycleHistoryPort.findLatestOneByStrategyId(infinite.id())).thenReturn(Optional.of(infiniteHistory));
        when(cycleHistoryPort.findLatestOneByStrategyId(privacy.id())).thenReturn(Optional.of(privacyHistory));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.of(privacyBase));
        when(strategyCycleVrPort.findByCycleId(vrCycle.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(vrCycle.strategyVersionId()))
                .thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(vrCycle.id())).thenReturn(BigDecimal.ZERO);
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.TQQQ), any(), any()))
                .thenReturn(List.of(buyTemplate(Ticker.TQQQ, "1500.00", Order.OrderTiming.AT_OPEN)));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate(Ticker.SOXL, "1000.00", Order.OrderTiming.AT_CLOSE)));
        when(privacyStrategy.buildOrders(any(), any(), any()))
                .thenReturn(List.of(buyTemplate(Ticker.SOXL, "1000.00", Order.OrderTiming.AT_CLOSE)));
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.TQQQ)))
                .thenReturn(new AccountBalance(100, new BigDecimal("20.00"), new BigDecimal("3000.00")));

        service.placeOpenOrders(List.of(
                new BatchContext(privacy, privacyCycle, ACCOUNT, USER),
                new BatchContext(infinite, infiniteCycle, ACCOUNT, USER),
                new BatchContext(vr, vrCycle, ACCOUNT, USER)), PAST_DST);

        verify(orderPort).saveAll(argThat(saved -> saved.stream()
                .allMatch(order -> order.strategyCycleId().equals(vrCycle.id()))));
        verify(orderPort).saveAll(argThat(saved -> saved.stream()
                .allMatch(order -> order.strategyCycleId().equals(infiniteCycle.id()))));
        verify(orderPort, never()).saveAll(argThat(saved -> saved.stream()
                .anyMatch(order -> order.strategyCycleId().equals(privacyCycle.id()))));
        verify(userNotificationPort).notifyInsufficientBalance(
                eq(USER), eq(ACCOUNT), eq(Strategy.Type.PRIVACY), eq(Ticker.SOXL));
    }

    @Test
    void placeOpenOrders_noSellOrders_skipsKisPlace() throws InterruptedException {
        // 후반 최종회차 등 SELL 없음 — KIS 접수 0건 (정상)
        BigDecimal prevClose = new BigDecimal("19.00");
        Order buyTemplate = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED, null, null, null)
                .withLeg("TEST_CLOSE_BUY");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, prevClose)));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate));
        // findAtOpenPlannedByCycleAndDate 미스텁 → Mockito 기본값 빈 목록 → KIS 접수 없음

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort).saveAll(anyList());
        verify(brokerOrderPort, never()).place(any(), any());
    }

    @Test
    void placeOpenOrders_interruptedAtMarketOpenWait_notifiesAllContextUsersAndRethrows() {
        // marketOpen을 살짝 미래로 설정 → waitUntilMarketOpen()이 양수 → Thread.sleep 실제 호출
        DstInfo interruptingDst = new DstInfo(true,
                Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(1800),
                Instant.now().plusMillis(300));

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));

        Thread.currentThread().interrupt();

        List<BatchContext> contexts = List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER));
        assertThrows(InterruptedException.class, () -> service.placeOpenOrders(contexts, interruptingDst));

        verify(userNotificationPort).notifyBatchInterrupted(USER, ACCOUNT);
        // 대기 단계에서 인터럽트되므로 order 생성·접수 루프는 시작조차 하지 않아야 함
        verify(orderPort, never()).saveAll(anyList());
    }

    @Test
    void executeBatch_liveBalanceInsufficient_skipsOrderPlanAndNotifies() throws InterruptedException {
        // 마감 스케쥴러 plan 단계 — live 잔고 부족 시 PLANNED 저장 건너뜀
        BigDecimal prevClose = new BigDecimal("19.00");
        Order bigBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 100, new BigDecimal("500.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_BIG_BUY");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(new BigDecimal("500.00"), prevClose)));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(bigBuy));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        // live 잔고 부족: BUY $50,000 > usdDeposit $10
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("10.00")));

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 알림 발송
        verify(userNotificationPort).notifyInsufficientBalance(eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
        // 저장 없음
        verify(orderPort, never()).saveAll(any());
        // 증권사 접수 없음
        verify(brokerOrderPort, never()).place(any(), any());
    }

    @Test
    void executeBatch_originalBuyFitsButPreparedBuyExceedsBudget_rejectsBuyAndSavesSell()
            throws InterruptedException {
        Order originalBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("30.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_ORIGINAL_BUY");
        Order sell = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL, 1, new BigDecimal("35.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_CLOSE_SELL");
        Order cappedBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 2, new BigDecimal("24.20"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_CAPPED_BUY");
        Order correction = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("10.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_CORRECTION_BUY");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(originalBuy, sell));
        when(infiniteStrategy.buildCappedBuyOrders(any(InfinitePosition.class), any(LocalDate.class),
                eq(List.of(originalBuy)), eq(new BigDecimal("24.20"))))
                .thenReturn(List.of(cappedBuy, correction));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        // 원본 BUY $30은 통과하지만 준비된 BUY $58.40은 가용 예산 $50을 초과한다.
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("50.00")));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort).saveAll(argThat(saved -> saved.size() == 1
                && saved.getFirst().direction() == Order.OrderDirection.SELL));
        verify(userNotificationPort).notifyInsufficientBalance(
                USER, ACCOUNT, Strategy.Type.INFINITE, Ticker.SOXL);
    }

    @Test
    void executeBatch_bothSidesRejectedWithoutExistingOrders_skipsPlacementAndReporting() throws InterruptedException {
        Order rejectedBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 100, new BigDecimal("500.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_REJECTED_BUY");
        Order rejectedSell = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL, 101, new BigDecimal("25.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_REJECTED_SELL");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(rejectedBuy, rejectedSell));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10.00")));
        when(sellableQuantityPort.getSellableQuantity(eq(Ticker.SOXL), eq(ACCOUNT)))
                .thenReturn(new SellableQuantity("SOXL", 100));

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort, never()).saveAll(anyList());
        verify(orderPort, never()).findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any());
        verify(cycleHistoryPort, never()).save(any());
        verify(userNotificationPort).notifyInsufficientBalance(USER, ACCOUNT, Strategy.Type.INFINITE, Ticker.SOXL);
    }

    @Test
    void executeBatch_liveBalanceFailure_doesNotStopOtherAccount() throws InterruptedException {
        Account failingAccount = account("00000000-0000-0000-0000-000000000001");
        Account succeedingAccount = account("00000000-0000-0000-0000-000000000002");
        Strategy failingStrategy = strategy(failingAccount);
        Strategy succeedingStrategy = strategy(succeedingAccount);
        StrategyCycle failingCycle = cycle(failingStrategy);
        StrategyCycle succeedingCycle = cycle(succeedingStrategy);
        User failingUser = DomainFixtures.activeUserWithTelegram(failingAccount.userId());
        User succeedingUser = DomainFixtures.activeUserWithTelegram(succeedingAccount.userId());
        Order buy = buyTemplate(Ticker.SOXL, "20.00", Order.OrderTiming.AT_CLOSE);
        Order succeedingPlanned = plannedBuy(succeedingAccount, succeedingCycle, "20.00");
        RuntimeException balanceFailure = new RuntimeException("account A balance failure");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(failingAccount)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(failingAccount))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(failingStrategy.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(cycleHistoryPort.findLatestOneByStrategyId(succeedingStrategy.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of(buy));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(any(), any())).thenReturn(List.of());
        when(liveBalancePort.getLiveBalance(eq(failingAccount), eq(Ticker.SOXL))).thenThrow(balanceFailure);
        when(liveBalancePort.getLiveBalance(eq(succeedingAccount), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00")));
        when(orderPort.findPlannedByCycleAndDate(eq(succeedingCycle.id()), any())).thenReturn(List.of(succeedingPlanned));
        when(brokerOrderPort.place(eq(succeedingPlanned), eq(succeedingAccount)))
                .thenReturn(succeedingPlanned.withPlaced("ORD-B-SUCCESS"));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(succeedingAccount))).thenReturn(List.of());

        service.executeBatch(List.of(
                new BatchContext(failingStrategy, failingCycle, failingAccount, failingUser),
                new BatchContext(succeedingStrategy, succeedingCycle, succeedingAccount, succeedingUser)), PAST_DST);

        verify(orderPort).saveAll(argThat(saved -> saved.stream()
                .allMatch(order -> order.accountId().equals(succeedingAccount.id()))));
        verify(brokerOrderPort).place(eq(succeedingPlanned), eq(succeedingAccount));
        verify(notifyPort).notifyError(balanceFailure);
    }

    @Test
    void executeBatch_saveFailure_doesNotStopOtherAccount() throws InterruptedException {
        Account failingAccount = account("00000000-0000-0000-0000-000000000001");
        Account succeedingAccount = account("00000000-0000-0000-0000-000000000002");
        Strategy failingStrategy = strategy(failingAccount);
        Strategy succeedingStrategy = strategy(succeedingAccount);
        StrategyCycle failingCycle = cycle(failingStrategy);
        StrategyCycle succeedingCycle = cycle(succeedingStrategy);
        User failingUser = DomainFixtures.activeUserWithTelegram(failingAccount.userId());
        User succeedingUser = DomainFixtures.activeUserWithTelegram(succeedingAccount.userId());
        Order buy = buyTemplate(Ticker.SOXL, "20.00", Order.OrderTiming.AT_CLOSE);
        Order succeedingPlanned = plannedBuy(succeedingAccount, succeedingCycle, "20.00");
        RuntimeException saveFailure = new RuntimeException("account A save failure");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(failingAccount)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(new BigDecimal("500.00"), new BigDecimal("19.00"))));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(failingAccount))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(failingStrategy.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(cycleHistoryPort.findLatestOneByStrategyId(succeedingStrategy.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of(buy));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(any(), any())).thenReturn(List.of());
        when(liveBalancePort.getLiveBalance(any(), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00")));
        doAnswer(invocation -> {
            List<Order> saved = invocation.getArgument(0);
            if (saved.getFirst().accountId().equals(failingAccount.id())) throw saveFailure;
            return null;
        }).when(orderPort).saveAll(anyList());
        when(orderPort.findPlannedByCycleAndDate(eq(succeedingCycle.id()), any())).thenReturn(List.of(succeedingPlanned));
        when(brokerOrderPort.place(eq(succeedingPlanned), eq(succeedingAccount)))
                .thenReturn(succeedingPlanned.withPlaced("ORD-B-SUCCESS"));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(succeedingAccount))).thenReturn(List.of());

        service.executeBatch(List.of(
                new BatchContext(failingStrategy, failingCycle, failingAccount, failingUser),
                new BatchContext(succeedingStrategy, succeedingCycle, succeedingAccount, succeedingUser)), PAST_DST);

        verify(orderPort, times(2)).saveAll(anyList());
        verify(brokerOrderPort).place(eq(succeedingPlanned), eq(succeedingAccount));
        verify(notifyPort).notifyError(saveFailure);
    }

    @Test
    void executeBatch_rejectionNotificationFailure_doesNotStopOtherAccount() throws InterruptedException {
        Account failingAccount = account("00000000-0000-0000-0000-000000000001");
        Account succeedingAccount = account("00000000-0000-0000-0000-000000000002");
        Strategy failingStrategy = strategy(failingAccount);
        Strategy succeedingStrategy = strategy(succeedingAccount);
        StrategyCycle failingCycle = cycle(failingStrategy);
        StrategyCycle succeedingCycle = cycle(succeedingStrategy);
        User failingUser = DomainFixtures.activeUserWithTelegram(failingAccount.userId());
        User succeedingUser = DomainFixtures.activeUserWithTelegram(succeedingAccount.userId());
        Order rejectedBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 100, new BigDecimal("500.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_REJECTED_BUY");
        RuntimeException notificationFailure = new RuntimeException("account A notification failure");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(failingAccount)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(new BigDecimal("500.00"), new BigDecimal("19.00"))));
        when(cycleHistoryPort.findLatestOneByStrategyId(failingStrategy.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(cycleHistoryPort.findLatestOneByStrategyId(succeedingStrategy.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of(rejectedBuy));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(any(), any())).thenReturn(List.of());
        when(liveBalancePort.getLiveBalance(any(), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10.00")));
        doThrow(notificationFailure).when(userNotificationPort).notifyInsufficientBalance(
                eq(failingUser), eq(failingAccount), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
        doThrow(new RuntimeException("secondary user notify failure"))
                .when(userNotificationPort).notifyError(eq(failingUser), eq(notificationFailure));

        service.executeBatch(List.of(
                new BatchContext(failingStrategy, failingCycle, failingAccount, failingUser),
                new BatchContext(succeedingStrategy, succeedingCycle, succeedingAccount, succeedingUser)), PAST_DST);

        verify(userNotificationPort).notifyInsufficientBalance(
                succeedingUser, succeedingAccount, Strategy.Type.INFINITE, Ticker.SOXL);
        verify(notifyPort).notifyError(notificationFailure);
    }

    @Test
    void executeBatch_privacyBuyRejected_stillSavesApprovedSell() throws InterruptedException {
        Strategy privacy = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.PRIVACY,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyCycle privacyCycle = new StrategyCycle(UUID.randomUUID(), privacy.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        CyclePosition history = new CyclePosition(null, privacyCycle.id(), new BigDecimal("1000.00"),
                PRICE, new BigDecimal("20.00"), 10, null, null);
        PrivacyTradeBase privacyBase = new PrivacyTradeBase(
                UUID.randomUUID(), new BigDecimal("20.00"), 10, new BigDecimal("20.00"), List.of());
        Order buy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 100, new BigDecimal("500.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_PRIVACY_BUY");
        Order sell = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_PRIVACY_SELL");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(privacy.id())).thenReturn(Optional.of(history));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.of(privacyBase));
        when(privacyStrategy.buildOrders(any(), any(), any())).thenReturn(List.of(buy, sell));
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10.00")));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(privacy, privacyCycle, ACCOUNT, USER)), PAST_DST);

        verify(orderPort).saveAll(argThat(saved -> saved.size() == 1
                && saved.getFirst().direction() == Order.OrderDirection.SELL
                && saved.getFirst().timing() == Order.OrderTiming.AT_CLOSE));
        verify(userNotificationPort).notifyInsufficientBalance(
                eq(USER), eq(ACCOUNT), eq(Strategy.Type.PRIVACY), eq(Ticker.SOXL));
    }

    @Test
    void executeBatch_existingAtOpenSell_doesNotBlockMissingAtCloseBuy() throws InterruptedException {
        Order existingSell = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL,
                1, new BigDecimal("25.00"), Order.OrderStatus.PLACED, "ORD-SELL-OPEN", null, null);
        Order buyTemplate = buyTemplate(Ticker.SOXL, "20.00", Order.OrderTiming.AT_CLOSE);
        Order buyPlanned = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LIMIT, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED, null, null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingSell));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(buyPlanned));
        when(orderPort.findPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(existingSell));
        when(brokerOrderPort.place(eq(buyPlanned), eq(ACCOUNT))).thenReturn(buyPlanned.withPlaced("ORD-BUY-CLOSE"));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort).saveAll(argThat(saved -> saved.size() == 1
                && saved.getFirst().timing() == Order.OrderTiming.AT_CLOSE
                && saved.getFirst().direction() == Order.OrderDirection.BUY));
        verify(brokerOrderPort).place(eq(buyPlanned), eq(ACCOUNT));
    }

    @Test
    void executeBatch_existingConcreteBuyLeg_savesOnlyMissingBuyLegs() throws InterruptedException {
        Order existing = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "INFINITE_EARLY_AVG_BUY", 1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order avg = buyTemplate(Ticker.SOXL, "20.00", Order.OrderTiming.AT_CLOSE).withLeg("INFINITE_EARLY_AVG_BUY");
        Order ref = buyTemplate(Ticker.SOXL, "22.00", Order.OrderTiming.AT_CLOSE).withLeg("INFINITE_EARLY_REF_BUY");
        Order correction = buyTemplate(Ticker.SOXL, "18.00", Order.OrderTiming.AT_CLOSE).withLeg("INFINITE_CORRECTION_01");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existing));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(avg, ref, correction));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(existing));

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort).saveAll(argThat(saved -> saved.size() == 2
                && saved.stream().map(Order::orderLeg).toList()
                .containsAll(List.of("INFINITE_EARLY_REF_BUY", "INFINITE_CORRECTION_01"))));
    }

    @Test
    void executeBatch_existingCompleteInfiniteEarlyConcreteLegs_skipsOrderComputer() throws InterruptedException {
        Order avg = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "INFINITE_EARLY_AVG_BUY", 1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order ref = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "INFINITE_EARLY_REF_BUY", 1, new BigDecimal("22.00"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order correction1 = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "INFINITE_CORRECTION_01", 1, new BigDecimal("19.00"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order correction2 = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "INFINITE_CORRECTION_02", 1, new BigDecimal("18.00"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order correction3 = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "INFINITE_CORRECTION_03", 1, new BigDecimal("17.00"), Order.OrderStatus.PLANNED,
                null, null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(avg, ref, correction1, correction2, correction3));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(avg, ref, correction1, correction2, correction3));
        when(brokerOrderPort.place(any(), eq(ACCOUNT)))
                .thenAnswer(invocation -> ((Order) invocation.getArgument(0)).withPlaced("ORD-001"));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(infiniteStrategy, never()).buildOrders(any(InfinitePosition.class), any(LocalDate.class));
        verify(brokerOrderPort, times(5)).place(any(), eq(ACCOUNT));
        verify(kisExecutionPort).getExecutions(any(), any(), any(), eq(ACCOUNT));
        verify(userNotificationPort).notifyTradingReport(eq(USER), eq(ACCOUNT), any(TradingReport.class));
    }

    @Test
    void executeBatch_existingInfiniteBaseWithoutCorrections_doesNotSkipOrderComputer() throws InterruptedException {
        Order avg = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "INFINITE_EARLY_AVG_BUY", 1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order ref = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "INFINITE_EARLY_REF_BUY", 1, new BigDecimal("22.00"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order correction = buyTemplate(Ticker.SOXL, "18.00", Order.OrderTiming.AT_CLOSE)
                .withLeg("INFINITE_CORRECTION_01");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(avg, ref));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(correction));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(avg, ref));

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(infiniteStrategy).buildOrders(any(InfinitePosition.class), any(LocalDate.class));
        verify(orderPort).saveAll(argThat(saved -> saved.size() == 1
                && saved.getFirst().orderLeg().equals("INFINITE_CORRECTION_01")));
    }

    @Test
    void placeOpenOrders_existingCompleteAtCloseBuyLegs_doesNotSkipMissingAtOpenSell() throws InterruptedException {
        Order avg = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "INFINITE_EARLY_AVG_BUY", 1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order ref = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "INFINITE_EARLY_REF_BUY", 1, new BigDecimal("22.00"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order correction1 = avg.withLeg("INFINITE_CORRECTION_01");
        Order correction2 = avg.withLeg("INFINITE_CORRECTION_02");
        Order correction3 = avg.withLeg("INFINITE_CORRECTION_03");
        Order sellTemplate = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("INFINITE_LOC_SELL");
        Order sellPlanned = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL,
                "INFINITE_LOC_SELL", 1, new BigDecimal("25.00"), Order.OrderStatus.PLANNED,
                null, null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(avg, ref, correction1, correction2, correction3));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(sellTemplate));
        when(orderPort.findAtOpenPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(sellPlanned));
        when(brokerOrderPort.place(eq(sellPlanned), eq(ACCOUNT)))
                .thenReturn(sellPlanned.withPlaced("ORD-OPEN-SELL"));

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(infiniteStrategy).buildOrders(any(InfinitePosition.class), any(LocalDate.class));
        verify(orderPort).saveAll(argThat(saved -> saved.size() == 1
                && saved.getFirst().orderLeg().equals("INFINITE_LOC_SELL")));
        verify(brokerOrderPort).place(eq(sellPlanned), eq(ACCOUNT));
    }

    @Test
    void executeBatch_unknownNewTemplateLeg_isRejectedBeforeSave() throws InterruptedException {
        Order unknown = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("20.00"),
                Order.OrderStatus.PLANNED, null, null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(unknown));

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort, never()).saveAll(anyList());
        verify(notifyPort).notifyError(argThat(error -> error instanceof IllegalStateException
                && error.getMessage().contains("전략 주문 leg 누락")));
    }

    @Test
    void executeBatch_existingPartialInfiniteConcreteLegs_doesNotSkipOrderComputer() throws InterruptedException {
        Order avg = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "INFINITE_EARLY_AVG_BUY", 1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order ref = buyTemplate(Ticker.SOXL, "22.00", Order.OrderTiming.AT_CLOSE)
                .withLeg("INFINITE_EARLY_REF_BUY");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(avg));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of(ref));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(avg));

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(infiniteStrategy).buildOrders(any(InfinitePosition.class), any(LocalDate.class));
    }

    @Test
    void executeBatch_existingUnknownBuyAndSell_skipsOrderComputer() throws InterruptedException {
        Order existingBuy = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED, null, null, null);
        Order existingSell = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL,
                1, new BigDecimal("25.00"), Order.OrderStatus.PLANNED, null, null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingBuy, existingSell));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingBuy, existingSell));

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort, never()).saveAll(anyList());
        verify(infiniteStrategy, never()).buildOrders(any(InfinitePosition.class), any(LocalDate.class));
    }

    @Test
    void executeBatch_existingUnknownSell_computesAndSavesReverseBuy() throws InterruptedException {
        Order existingSell = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL,
                1, new BigDecimal("25.00"), Order.OrderStatus.PLANNED, null, null, null);
        Order reverseBuy = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, new BigDecimal("21.99"), "REVERSE_INFINITE_LOC_BUY");
        CyclePositionInfiniteDetail latestReverse = new CyclePositionInfiniteDetail(UUID.randomUUID(), true);
        CyclePositionInfiniteDetail previousReverse = new CyclePositionInfiniteDetail(UUID.randomUUID(), true);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(cycleHistoryPort.findLatestByCycleId(STRATEGY_CYCLE.id(), 5)).thenReturn(List.of(NORMAL_HISTORY));
        when(cyclePositionInfiniteDetailPort.findLatestByCycleId(STRATEGY_CYCLE.id(), 2))
                .thenReturn(List.of(latestReverse, previousReverse));
        when(reverseStrategy.buildOrders(any(ReverseModePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(reverseBuy));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingSell));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(existingSell));
        when(brokerOrderPort.place(eq(existingSell), eq(ACCOUNT)))
                .thenReturn(existingSell.withPlaced("ORD-SELL-001"));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(reverseStrategy).buildOrders(any(ReverseModePosition.class), any(LocalDate.class));
        verify(orderPort).saveAll(argThat(saved -> saved.size() == 1
                && saved.getFirst().timing() == Order.OrderTiming.AT_CLOSE
                && saved.getFirst().direction() == Order.OrderDirection.BUY
                && saved.getFirst().orderLeg().equals("REVERSE_INFINITE_LOC_BUY")));
    }

    @Test
    void executeBatch_existingReverseBuyOnly_computesAndSavesReverseSell() throws InterruptedException {
        Order existingBuy = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "REVERSE_INFINITE_LOC_BUY", 1, new BigDecimal("21.99"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order reverseSell = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.SELL, 1, new BigDecimal("22.00"), "REVERSE_INFINITE_LOC_SELL");
        CyclePositionInfiniteDetail latestReverse = new CyclePositionInfiniteDetail(UUID.randomUUID(), true);
        CyclePositionInfiniteDetail previousReverse = new CyclePositionInfiniteDetail(UUID.randomUUID(), true);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(cycleHistoryPort.findLatestByCycleId(STRATEGY_CYCLE.id(), 5)).thenReturn(List.of(NORMAL_HISTORY));
        when(cyclePositionInfiniteDetailPort.findLatestByCycleId(STRATEGY_CYCLE.id(), 2))
                .thenReturn(List.of(latestReverse, previousReverse));
        when(reverseStrategy.buildOrders(any(ReverseModePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(reverseSell));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingBuy));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(existingBuy));
        when(brokerOrderPort.place(eq(existingBuy), eq(ACCOUNT)))
                .thenReturn(existingBuy.withPlaced("ORD-BUY-001"));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(reverseStrategy).buildOrders(any(ReverseModePosition.class), any(LocalDate.class));
        verify(orderPort).saveAll(argThat(saved -> saved.size() == 1
                && saved.getFirst().timing() == Order.OrderTiming.AT_CLOSE
                && saved.getFirst().direction() == Order.OrderDirection.SELL
                && saved.getFirst().orderLeg().equals("REVERSE_INFINITE_LOC_SELL")));
    }

    @Test
    void executeBatch_existingReverseSellLegWithBuyDirection_computesAndSavesReverseSell() throws InterruptedException {
        Order existingBuy = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "REVERSE_INFINITE_LOC_BUY", 1, new BigDecimal("21.99"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order wrongDirectionSellLeg = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                "REVERSE_INFINITE_LOC_SELL", 1, new BigDecimal("22.00"), Order.OrderStatus.PLANNED,
                null, null, null);
        Order reverseSell = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.SELL, 1, new BigDecimal("22.00"), "REVERSE_INFINITE_LOC_SELL");
        CyclePositionInfiniteDetail latestReverse = new CyclePositionInfiniteDetail(UUID.randomUUID(), true);
        CyclePositionInfiniteDetail previousReverse = new CyclePositionInfiniteDetail(UUID.randomUUID(), true);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(cycleHistoryPort.findLatestByCycleId(STRATEGY_CYCLE.id(), 5)).thenReturn(List.of(NORMAL_HISTORY));
        when(cyclePositionInfiniteDetailPort.findLatestByCycleId(STRATEGY_CYCLE.id(), 2))
                .thenReturn(List.of(latestReverse, previousReverse));
        when(reverseStrategy.buildOrders(any(ReverseModePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(reverseSell));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingBuy, wrongDirectionSellLeg));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingBuy, wrongDirectionSellLeg));
        when(brokerOrderPort.place(eq(existingBuy), eq(ACCOUNT)))
                .thenReturn(existingBuy.withPlaced("ORD-BUY-001"));
        when(brokerOrderPort.place(eq(wrongDirectionSellLeg), eq(ACCOUNT)))
                .thenReturn(wrongDirectionSellLeg.withPlaced("ORD-WRONG-001"));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(reverseStrategy).buildOrders(any(ReverseModePosition.class), any(LocalDate.class));
        verify(orderPort).saveAll(argThat(saved -> saved.size() == 1
                && saved.getFirst().timing() == Order.OrderTiming.AT_CLOSE
                && saved.getFirst().direction() == Order.OrderDirection.SELL
                && saved.getFirst().orderLeg().equals("REVERSE_INFINITE_LOC_SELL")));
    }

    @Test
    void executeBatch_computeEmptyWithExistingOrders_preservesPlacement() throws InterruptedException {
        Strategy privacy = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.PRIVACY,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyCycle privacyCycle = new StrategyCycle(UUID.randomUUID(), privacy.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        CyclePosition history = new CyclePosition(null, privacyCycle.id(), new BigDecimal("1000.00"),
                PRICE, new BigDecimal("20.00"), 10, null, null);
        Order existingSell = new Order(UUID.randomUUID(), ACCOUNT.id(), privacyCycle.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL,
                1, new BigDecimal("25.00"), Order.OrderStatus.PLANNED, null, null, null);

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(privacy.id())).thenReturn(Optional.of(history));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.empty());
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(privacyCycle.id()), any()))
                .thenReturn(List.of(existingSell));
        when(orderPort.findPlannedByCycleAndDate(eq(privacyCycle.id()), any())).thenReturn(List.of(existingSell));
        when(brokerOrderPort.place(eq(existingSell), eq(ACCOUNT)))
                .thenReturn(existingSell.withPlaced("ORD-PRIVACY-SELL"));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(privacy, privacyCycle, ACCOUNT, USER)), PAST_DST);

        verify(orderPort, never()).saveAll(anyList());
        verify(brokerOrderPort).place(eq(existingSell), eq(ACCOUNT));
        verify(privacyStrategy, never()).buildOrders(any(), any(), any());
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
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        // 오늘 주문 이미 존재 (SELL은 PLACED, BUY는 PLANNED)
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingSell, existingBuy));
        // BUY 잔고 유효 ($22 < $1000)
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingBuy)); // PLANNED BUY만
        // PLACED SELL 조회 (placeAll에서 prePlacedSells)
        when(orderPort.findPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingSell));
        when(brokerOrderPort.place(any(), eq(ACCOUNT))).thenReturn(placedBuy);
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 재계산은 했지만 saveAll은 없음
        verify(orderPort, never()).saveAll(any());
        // BUY 접수
        verify(brokerOrderPort).place(any(), eq(ACCOUNT));
        // 잔고 충분 — 사용자 알람 없음
        verify(userNotificationPort, never()).notifyInsufficientBalance(any(), any(), any(), any());
    }

    // ── executeBatch 테스트 ────────────────────────────────────────────────────

    @Test
    void executeBatch_fetchesPricesTwice_startAndClose_notPerCycle() throws InterruptedException {
        // 두 전략이 같은 ticker → getPriceSnapshots() 1회(시작가), getPrices() 1회(종가), 단건 fallback 없음
        Strategy strategy2 = new Strategy(UUID.randomUUID(), ACCOUNT.id(),
                Strategy.Type.INFINITE, Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyCycle cycle2 = new StrategyCycle(UUID.randomUUID(), strategy2.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        CyclePosition history2 = new CyclePosition(
                null, cycle2.id(), new BigDecimal("1000.00"), new BigDecimal("20.00"), new BigDecimal("20.00"), 10, null, null);
        Order existingFirst = placedOrder(ACCOUNT, STRATEGY_CYCLE);
        Order existingSecond = placedOrder(ACCOUNT, cycle2);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(cycleHistoryPort.findLatestOneByStrategyId(strategy2.id())).thenReturn(Optional.of(history2));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(existingFirst));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(cycle2.id()), any())).thenReturn(List.of(existingSecond));
        when(orderPort.findPlannedByCycleAndDate(any(), any())).thenReturn(List.of());
        when(orderPort.findPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(existingFirst));
        when(orderPort.findPlacedByCycleAndDate(eq(cycle2.id()), any())).thenReturn(List.of(existingSecond));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

        service.executeBatch(List.of(
                new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER),
                new BatchContext(strategy2, cycle2, ACCOUNT, USER)
        ), PAST_DST);

        verify(kisPricePort).getPriceSnapshots(anyList(), eq(ACCOUNT)); // 시작가(Phase A) 1회
        verify(kisPricePort).getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT));         // 종가(PostClose) 1회
        verify(kisPricePort, never()).getPrice(any(), any());
        verify(kisPricePort, never()).getPriceSnapshot(any(), any());
    }

    @Test
    void executeBatch_oneCycleFails_continuesWithNextAndNotifiesAdmin() throws InterruptedException {
        // STRATEGY → 예외 발생, strategy2 → 정상 실행
        Strategy strategy2 = new Strategy(UUID.randomUUID(), ACCOUNT.id(),
                Strategy.Type.INFINITE, Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle cycle2 = new StrategyCycle(UUID.randomUUID(), strategy2.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        CyclePosition history2 = new CyclePosition(
                null, cycle2.id(), new BigDecimal("1000.00"), new BigDecimal("20.00"), new BigDecimal("20.00"), 10, null, null);
        Order existingSecond = placedOrder(ACCOUNT, cycle2);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE), Ticker.TQQQ, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE, Ticker.TQQQ, PRICE));
        // STRATEGY: 잔고 조회에서 예외
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        RuntimeException ex = new RuntimeException("잔고 조회 오류");
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenThrow(ex);
        when(cycleHistoryPort.findLatestOneByStrategyId(strategy2.id())).thenReturn(Optional.of(history2));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(cycle2.id()), any())).thenReturn(List.of(existingSecond));
        when(orderPort.findPlannedByCycleAndDate(any(), any())).thenReturn(List.of());
        when(orderPort.findPlacedByCycleAndDate(eq(cycle2.id()), any())).thenReturn(List.of(existingSecond));
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
    void executeBatch_interruptedAtOrderWait_notifiesPlannedUserAndRethrows() {
        // orderAt을 살짝 미래로 설정 → waitUntilOrderTime()이 양수 → Thread.sleep 실제 호출
        DstInfo interruptingDst = new DstInfo(true,
                Instant.now().plusMillis(300),
                Instant.now().minusSeconds(1800),
                Instant.now().minusSeconds(7200));

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any(LocalDate.class)))
                .thenReturn(List.of(placedOrder(ACCOUNT, STRATEGY_CYCLE)));

        // waitFor 진입 시 Thread.sleep이 즉시 InterruptedException을 던지도록 인터럽트 플래그 선-설정
        Thread.currentThread().interrupt();

        List<BatchContext> contexts = List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER));
        assertThrows(InterruptedException.class, () -> service.executeBatch(contexts, interruptingDst));

        verify(userNotificationPort).notifyBatchInterrupted(USER, ACCOUNT);
    }

    @Test
    void executeBatch_getPricesFails_cycleFailsAndNotifiesAdmin() throws InterruptedException {
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenThrow(new RuntimeException("API 오류"));
        // getPriceSnapshot 단건 fallback도 실패 → snapshot=null → price=null + prevClosePrice=null → holdings=0 → IllegalStateException
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(FRESH_HISTORY));

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
                UUID.randomUUID(), maintainStrategy.id(), UUID.randomUUID(), initDeposit, null, LocalDate.now(), null, null, null);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestOneByStrategyId(maintainStrategy.id())).thenReturn(Optional.of(FRESH_HISTORY));
        // 사이클 종료 판정: 이전 포지션 holdings > 0 → 진짜 청산으로 판단 (limit 무관, CycleOrderComputer=2, Reporter=1)
        when(cycleHistoryPort.findLatestByCycleId(eq(maintainCycle.id()), anyInt())).thenReturn(List.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(maintainCycle.id()), any())).thenReturn(List.of(placedOrder(ACCOUNT, maintainCycle)));
        when(orderPort.findPlannedByCycleAndDate(eq(maintainCycle.id()), any())).thenReturn(List.of());
        when(orderPort.findPlacedByCycleAndDate(eq(maintainCycle.id()), any())).thenReturn(List.of(placedOrder(ACCOUNT, maintainCycle)));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        // MAINTAIN은 KIS 실잔고 확인 필수 — initDeposit 이상이면 재등록
        when(kisMarginBrokerPort.getUsdBuyableAmount(ACCOUNT)).thenReturn(initDeposit);

        service.executeBatch(List.of(new BatchContext(maintainStrategy, maintainCycle, ACCOUNT, USER)), PAST_DST);

        // 재등록: StrategyCycle + CyclePosition 원자 저장이 startAmount=initDeposit으로 호출됨
        verify(cycleSnapshotCreator).createCycleAndSnapshot(
                eq(maintainStrategy.id()), any(UUID.class), eq(initDeposit), eq(PRICE));
        // 성공 알림 발송
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
                UUID.randomUUID(), maxStrategy.id(), UUID.randomUUID(), new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);
        BigDecimal expectedSeed = FRESH_HISTORY.usdDeposit(); // 1000.00

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestOneByStrategyId(maxStrategy.id())).thenReturn(Optional.of(FRESH_HISTORY));
        // 사이클 종료 판정: 이전 포지션 holdings > 0 → 진짜 청산으로 판단 (limit 무관, CycleOrderComputer=2, Reporter=1)
        when(cycleHistoryPort.findLatestByCycleId(eq(maxCycle.id()), anyInt())).thenReturn(List.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of(placedOrder(ACCOUNT, maxCycle)));
        when(orderPort.findPlannedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of());
        when(orderPort.findPlacedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of(placedOrder(ACCOUNT, maxCycle)));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(kisMarginBrokerPort.getUsdBuyableAmount(ACCOUNT)).thenReturn(marginAmount);

        service.executeBatch(List.of(new BatchContext(maxStrategy, maxCycle, ACCOUNT, USER)), PAST_DST);

        // 재등록: 내부 원장(maxSeed=expectedSeed)으로 원자 저장
        verify(cycleSnapshotCreator).createCycleAndSnapshot(
                eq(maxStrategy.id()), any(UUID.class), eq(expectedSeed), eq(PRICE));
        // 성공 알림 발송
    }

    @Test
    void executeBatch_MAX_belowMinRequired_skipsRotationAndNotifiesInsufficientBalance() throws InterruptedException {
        // PRICE=22, minRequired = 22 × (20 × 2.2) = 968
        // actualBalance=500, maintainSeed=500 → targetSeed=500 < 968 → notifyInsufficientBalance
        BigDecimal marginAmount = new BigDecimal("500.00");
        Strategy maxStrategy = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAX);
        StrategyCycle maxCycle = new StrategyCycle(
                UUID.randomUUID(), maxStrategy.id(), UUID.randomUUID(), new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestOneByStrategyId(maxStrategy.id())).thenReturn(Optional.of(FRESH_HISTORY));
        // 사이클 종료 판정: 이전 포지션 holdings > 0 → 진짜 청산으로 판단 (limit 무관, CycleOrderComputer=2, Reporter=1)
        when(cycleHistoryPort.findLatestByCycleId(eq(maxCycle.id()), anyInt())).thenReturn(List.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of(placedOrder(ACCOUNT, maxCycle)));
        when(orderPort.findPlannedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of());
        when(orderPort.findPlacedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of(placedOrder(ACCOUNT, maxCycle)));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(kisMarginBrokerPort.getUsdBuyableAmount(ACCOUNT)).thenReturn(marginAmount);

        service.executeBatch(List.of(new BatchContext(maxStrategy, maxCycle, ACCOUNT, USER)), PAST_DST);

        verify(strategyCyclePort, never()).save(any());
        verify(notifyPort).notifyInsufficientBalance(eq(ACCOUNT), any(AccountBalance.class), eq(Ticker.SOXL));
    }

    @Test
    void executeBatch_firstDayBuyFails_doesNotEndCycle() throws InterruptedException {
        // 0회차(holdings=0) 매수 실패 — 이전 포지션이 initialSnapshot(holdings=0)이므로 사이클 종료가 아님
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(FRESH_HISTORY)); // holdings=0
        // 이전 포지션도 holdings=0 (initialSnapshot) — 진짜 청산 아님
        when(cycleHistoryPort.findLatestByCycleId(eq(STRATEGY_CYCLE.id()), anyInt())).thenReturn(List.of(FRESH_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(placedOrder(ACCOUNT, STRATEGY_CYCLE)));
        when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of());
        when(orderPort.findPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(placedOrder(ACCOUNT, STRATEGY_CYCLE)));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of()); // 체결 없음

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 0회차 매수 실패 → 사이클 종료 처리 금지
        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(userNotificationPort, never()).notifyCycleCompleted(any(), any(), any());
        verify(strategyCyclePort, never()).save(any()); // 새 사이클 재등록도 없음
    }

    // ── VR 전략 executeBatch 테스트 ──────────────────────────────────────────────

    @Test
    void executeBatch_vrStrategy_doesNotRecreateAtOpenOrdersAtClose() throws InterruptedException {
        // VR 전략 + 사이클 픽스처 (STRATEGY/STRATEGY_CYCLE은 INFINITE 전용이므로 별도 생성)
        Strategy vrStrat = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        UUID vrVersionId = UUID.randomUUID();
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vrStrat.id(), vrVersionId,
                new BigDecimal("5000.00"), null, LocalDate.now(), null, null, null);
        // VR 잔고 이력 — holdings=5, usdDeposit=$5000 (live 잔고 검증 통과용)
        CyclePosition vrHistory = new CyclePosition(
                null, vrCycle.id(), new BigDecimal("5000.00"), new BigDecimal("22.00"),
                new BigDecimal("20.00"), 5, null, null);

        // VR 사이클·버전 상세 — CycleOrderComputer에서 VrInputs 조립 시 조회됨
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                vrCycle.id(), new BigDecimal("1000.00"), 10, new BigDecimal("2500.00"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(vrVersionId, 4, new BigDecimal("15.00"), 0);

        // VR buildOrders 결과: LIMIT + AT_OPEN 주문만 반환 (매수 1주 + 매도 1주)
        Order vrBuyTemplate = new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.BUY,
                1, new BigDecimal("22.00"), Order.OrderStatus.PLANNED, null, null, null)
                .withLeg("TEST_VR_BUY");
        Order vrSellTemplate = new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL,
                1, new BigDecimal("25.00"), Order.OrderStatus.PLANNED, null, null, null)
                .withLeg("TEST_VR_SELL");
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        // 잔고: VR도 cycle_position DB 이력에서 로드 (TradingBalanceLoader.loadBalanceOrThrow)
        when(cycleHistoryPort.findLatestOneByStrategyId(vrStrat.id())).thenReturn(Optional.of(vrHistory));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(vrCycle.id()), any())).thenReturn(List.of());
        // VR 전용 포트 — CycleOrderComputer가 VrInputs 조립 시 호출
        when(strategyCycleVrPort.findByCycleId(vrCycle.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(vrVersionId)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(vrCycle.id())).thenReturn(BigDecimal.ZERO);
        // buildOrders: VR 전략은 LIMIT + AT_OPEN 주문만 반환
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), any(), any()))
                .thenReturn(List.of(vrBuyTemplate, vrSellTemplate));

        service.executeBatch(List.of(new BatchContext(vrStrat, vrCycle, ACCOUNT, USER)), PAST_DST);

        // VR 전용 포트 호출 검증 — CycleOrderComputer가 VrInputs를 올바르게 조립했음을 확인
        verify(strategyCycleVrPort).findByCycleId(vrCycle.id());
        verify(strategyVrDetailPort).findByStrategyVersionId(vrVersionId);
        verify(orderPort).sumFilledBuyAmountByCycleId(vrCycle.id());
        verify(orderPort, never()).saveAll(argThat(saved ->
                saved.stream().anyMatch(order -> order.timing() == Order.OrderTiming.AT_OPEN)));
        verify(brokerOrderPort, never()).place(any(), any());
        // VR only 배치 — PRIVACY 기준매매표 조회가 발생하지 않아야 함
        verify(privacyTradePort, never()).findTodayTrade(any());
    }

    private Account account(String accountId) {
        UUID id = UUID.fromString(accountId);
        return DomainFixtures.kisAccount(id, UUID.nameUUIDFromBytes(("user-" + accountId).getBytes()));
    }

    private Strategy strategy(Account account) {
        return new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
    }

    private StrategyCycle cycle(Strategy strategy) {
        return new StrategyCycle(UUID.randomUUID(), strategy.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
    }

    private Order plannedBuy(Account account, StrategyCycle cycle, String price) {
        return new Order(UUID.randomUUID(), account.id(), cycle.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                1, new BigDecimal(price), Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order placedOrder(Account account, StrategyCycle cycle) {
        return new Order(UUID.randomUUID(), account.id(), cycle.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL,
                1, new BigDecimal("25.00"), Order.OrderStatus.PLACED, "ORD-EXISTING", null, null);
    }

    private Order buyTemplate(Ticker ticker, String amount, Order.OrderTiming timing) {
        return new Order(null, null, null, LocalDate.now(), ticker, Order.OrderType.LIMIT,
                timing, Order.OrderDirection.BUY, 1, new BigDecimal(amount),
                Order.OrderStatus.PLANNED, null, null, null)
                .withLeg("TEST_" + ticker + "_" + timing + "_BUY_" + amount.replace(".", "_"));
    }

    @Test
    void executeBatch_MAX_marginFails_skipsRotationAndNotifiesError() throws InterruptedException {
        Strategy maxStrategy = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAX);
        StrategyCycle maxCycle = new StrategyCycle(
                UUID.randomUUID(), maxStrategy.id(), UUID.randomUUID(), new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);
        RuntimeException kisError = new RuntimeException("KIS 증거금 조회 실패");

        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(cycleHistoryPort.findLatestOneByStrategyId(maxStrategy.id())).thenReturn(Optional.of(FRESH_HISTORY));
        // 사이클 종료 판정: 이전 포지션 holdings > 0 → 진짜 청산으로 판단 (limit 무관, CycleOrderComputer=2, Reporter=1)
        when(cycleHistoryPort.findLatestByCycleId(eq(maxCycle.id()), anyInt())).thenReturn(List.of(NORMAL_HISTORY));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of(placedOrder(ACCOUNT, maxCycle)));
        when(orderPort.findPlannedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of());
        when(orderPort.findPlacedByCycleAndDate(eq(maxCycle.id()), any())).thenReturn(List.of(placedOrder(ACCOUNT, maxCycle)));
        when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());
        when(kisMarginBrokerPort.getUsdBuyableAmount(ACCOUNT)).thenThrow(kisError);

        service.executeBatch(List.of(new BatchContext(maxStrategy, maxCycle, ACCOUNT, USER)), PAST_DST);

        verify(strategyCyclePort, never()).save(any());
        // rotateCycleIfConsecutive 내부 catch → notifyError (executeBatch 바깥 catch와 별개)
        verify(notifyPort, atLeastOnce()).notifyError(any());
    }
}
