package com.kista.trading.application.service;
import com.kista.trading.application.service.support.TradingOrderPlanner;
import com.kista.trading.application.service.support.TradingBalanceLoader;

import com.kista.sharedkernel.OrderStatus;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.SellableQuantity;
import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.TradingAccount;
import com.kista.trading.domain.model.ManualTradingException;
import com.kista.trading.domain.model.Order;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.trading.domain.model.Strategy; import com.kista.trading.domain.model.*;
import com.kista.matching.domain.model.*;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.privacy.application.port.output.PrivacyTradePort; import com.kista.trading.application.port.output.*;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.broker.application.port.output.LiveBalancePort;
import com.kista.broker.application.port.output.SellableQuantityPort;
import com.kista.trading.application.port.output.StrategyCycleVrPort;
import com.kista.trading.application.port.output.StrategyVrDetailPort;
import com.kista.trading.domain.strategy.*;
import com.kista.matching.domain.strategy.*;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.support.TradingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
class ManualTradingServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock AccountPort accountPort;
    @Mock OrderPort orderPort;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock BrokerPricePort kisPricePort;      // BrokerPricePort 직접 mock (KisPricePort 삭제됨)
    @Mock CyclePositionPort cyclePositionPort;
    @Mock CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    @Mock StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    @Mock LiveBalancePort liveBalancePort;   // LiveBalancePort 직접 mock
    @Mock SellableQuantityPort sellableQuantityPort;
    @Mock TradingOrderExecutor orderExecutor;
    @Mock InfiniteStrategy infiniteStrategy; // class-level — 테스트별로 stub 가능
    @Mock StrategyCycleVrPort strategyCycleVrPort; // CycleOrderComputer VR 분기용
    @Mock StrategyVrDetailPort strategyVrDetailPort; // CycleOrderComputer VR 분기용
    @Mock VrStrategy vrStrategy; // VrCycleOrderStrategy 조립용
    @Mock PrivacyBaseGuard privacyBaseGuard; // PRIVACY 기준표 장전 점검
    @Mock ApplicationEventPublisher eventPublisher; // 4xx 예외라 GlobalExceptionHandler가 저장 안 하는 외부 API 실패를 직접 기록

    ManualTradingService service;

    static final UUID REQUESTER_ID = UUID.randomUUID();
    static final Account ACCOUNT = TradingFixtures.kisAccount(UUID.randomUUID(), REQUESTER_ID);
    static final BrokerAccountRef ACCOUNT_REF = ACCOUNT.toBrokerRef();
    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
            StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE
    );
    static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();
    static final StrategyCycle CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), STRATEGY_VERSION_ID, new BigDecimal("1000.00"), null,
            LocalDate.now(), null, null, null
    );
    // DB 잔고 이력 — cycle_position 기반 (TradingBalanceLoader가 읽음)
    static final CyclePosition HISTORY = new CyclePosition(
            null, CYCLE.id(), new BigDecimal("1000.00"), new BigDecimal("22.00"),
            new BigDecimal("20.00"), 10, null, null
    );

    AccountBudgetLock budgetLock = spy(new AccountBudgetLock()); // 실제 락 + 구간 추적

    @BeforeEach
    void setUp() {
        // 실제 헬퍼 컴포넌트 조립 — TradingServiceTest 패턴 동일
        TradingBalanceLoader balanceLoader = new TradingBalanceLoader(cyclePositionPort);
        ReverseInfiniteStrategy reverseStrategy = mock(ReverseInfiniteStrategy.class);
        PrivacyStrategy privacyStrategy = mock(PrivacyStrategy.class);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy),
                new PrivacyCycleOrderStrategy(privacyStrategy),
                new VrCycleOrderStrategy(vrStrategy))); // VR 수동 실행 테스트용
        CycleOrderComputer orderComputer = new CycleOrderComputer(
                cycleStrategies, cyclePositionPort, cyclePositionInfiniteDetailPort, strategyInfiniteDetailPort,
                strategyCycleVrPort, strategyVrDetailPort, orderPort);
        TradingOrderPlanner orderPlanner = new TradingOrderPlanner(orderPort);
        TradingPriceFetcher priceFetcher = new TradingPriceFetcher(kisPricePort, eventPublisher, privacyTradePort);
        StrategyOrderPlanBuilder planBuilder = new StrategyOrderPlanBuilder(
                balanceLoader, kisPricePort, privacyTradePort, orderComputer, cycleStrategies, privacyBaseGuard);
        BuyOrderPriceCapper priceCapper = new BuyOrderPriceCapper(
                orderPort, orderPlanner, cycleStrategies, strategyCyclePort);
        TradingOrderBudgetAllocator budgetAllocator = new TradingOrderBudgetAllocator(
                liveBalancePort, sellableQuantityPort, orderPort, cycleStrategies);

        // ManualTradingService 필드 목록에 orderPlanner가 포함돼 있다(배치 TradingCandidatePlanner와 동일하게
        // allocator 승인 결과를 저장하는 데 필요 — 브리핑 원안 생성자 호출에는 누락돼 있어 여기서 보정한다)
        service = new ManualTradingService(
                strategyPort, strategyCyclePort, accountPort, orderPort,
                priceFetcher, planBuilder, priceCapper, budgetAllocator,
                orderPlanner, orderExecutor, budgetLock, eventPublisher);
        lenient().when(sellableQuantityPort.getSellableQuantity(any(), any()))
                .thenReturn(new SellableQuantity("SOXL", 100));

        lenient().when(strategyPort.findByIdOrThrow(STRATEGY.id())).thenReturn(STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT.id(), REQUESTER_ID)).thenReturn(ACCOUNT);
        lenient().when(strategyCyclePort.requireLatestByStrategyId(STRATEGY.id())).thenReturn(CYCLE);
        lenient().when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of());
        lenient().when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(HISTORY));
        lenient().when(cyclePositionPort.findLatestByCycleId(eq(CYCLE.id()), anyInt())).thenReturn(List.of(HISTORY));
        lenient().when(cyclePositionInfiniteDetailPort.findLatestByCycleId(eq(CYCLE.id()), anyInt())).thenReturn(List.of());
        lenient().when(strategyInfiniteDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 40)));
        lenient().when(strategyInfiniteDetailPort.findActiveByStrategyId(STRATEGY.id()))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 40)));
        // planBuilder는 INFINITE/VR 둘 다 requiresPrevClose()=true라 전일종가를 항상 조회한다(BrokerPricePort.getPrevClose 경유,
        // priceFetcher.fetchPriceSnapshots와 별도) — holdings>0인 기존 픽스처는 이 값을 쓰지 않지만 VR referencePrice 폴백에는 필요
        lenient().when(kisPricePort.getPrevClose(eq(StrategyTicker.SOXL), eq(ACCOUNT_REF)))
                .thenReturn(new BigDecimal("20.00"));
        // 캡 판단용 현재가 — 기존 주문 픽스처 가격(22.00)에 캡(×1.05=23.10)이 걸리지 않는 기본값(개별 테스트가 필요 시 override)
        lenient().when(kisPricePort.getPrices(anyList(), eq(ACCOUNT_REF)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("22.00")));
    }

    @Test
    void execute_insufficientSellHoldings_throwsManualTradingException() {
        // SELL 15주 계획, live holdings=10 → 보유수량 부족 → ManualTradingException
        Order sellOrder = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_OPEN,
                OrderDirection.SELL, 15, new BigDecimal("22.00"),
                OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(sellOrder.toPlanned()));
        // live holdings=10, sellable=10 < SELL 15주 — 보유수량 부족
        when(sellableQuantityPort.getSellableQuantity(any(), any()))
                .thenReturn(new SellableQuantity("SOXL", 10));

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingException.class)
                .hasMessageContaining("보유 수량이 부족합니다");
        // BUY 후보가 없으므로 allocator가 LiveBalancePort를 조회하지 않아야 한다
        verify(liveBalancePort, never()).getLiveBalance(any(), any());
    }

    @Test
    void execute_liveBalanceBrokerApiFailure_propagatesAsIsFor503() {
        // 증권사 타입 예외는 409로 감싸지 않고 그대로 전파 — TradingExceptionHandler가 503 매핑·에러 로그 기록
        Order buyOrder = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_OPEN,
                OrderDirection.BUY, 1, new BigDecimal("22.00"),
                OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyOrder.toPlanned()));
        RuntimeException brokerFailure = new com.kista.support.StubBrokerApiException("Toss", "Toss API 오류",
                com.kista.broker.domain.model.BrokerApiException.Conflict.NONE);
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL))).thenThrow(brokerFailure);

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID)).isSameAs(brokerFailure);

        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    void execute_existingOrderToday_throwsAlreadyOrderedToday() {
        Order existing = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_OPEN,
                OrderDirection.BUY, 1, new BigDecimal("22.00"),
                OrderStatus.PLANNED, null, null, null);
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(AlreadyOrderedTodayException.class);
    }

    @Test
    void execute_liveBalanceFetchFails_notifiesAdminAndThrowsManualTradingFailed() {
        // 증권사 타입이 아닌 예상 밖 예외는 500(ManualTradingFailedException)으로 승격 — 핸들러는 보고하지 않으므로 서비스가 TradingErrorEvent를 발행
        Order buyOrder = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_OPEN,
                OrderDirection.BUY, 1, new BigDecimal("22.00"),
                OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyOrder.toPlanned()));
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenThrow(new RuntimeException("Toss API 오류"));

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingFailedException.class).hasCauseInstanceOf(RuntimeException.class);

        verify(eventPublisher).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    void execute_existingReservedSellExceedsAvailable_rejects() {
        Order sellOrder = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_OPEN,
                OrderDirection.SELL, 3, new BigDecimal("22.00"),
                OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(sellOrder.toPlanned()));
        when(sellableQuantityPort.getSellableQuantity(any(), any()))
                .thenReturn(new SellableQuantity("SOXL", 5));
        when(orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                eq(ACCOUNT.id()), any(LocalDate.class), eq(StrategyTicker.SOXL)))
                .thenReturn(3);

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingException.class)
                .hasMessage("보유 수량이 부족합니다");

        verify(orderPort, never()).saveAll(anyList());
        // BUY 후보가 없으므로 allocator가 LiveBalancePort를 조회하지 않아야 한다
        verify(liveBalancePort, never()).getLiveBalance(any(), any());
    }

    @Test
    void execute_sufficientBalance_savesOrders() {
        // BUY 1주, live 충분(usdDeposit=$10,000, holdings=10) → saveAll 호출, 주문 반환
        Order buyTemplate = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("20.00"),
                OrderStatus.PLANNED, null, null, null);
        Order savedOrder = new Order(UUID.randomUUID(), ACCOUNT.id(), CYCLE.id(), LocalDate.now(),
                StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("20.00"),
                OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate.toPlanned()));
        // live 잔고 충분: usdDeposit=$10,000 > BUY $20
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(orderPort.findPlannedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of()); // AT_OPEN 없음(BUY뿐) — 개장 후에만 호출되므로 lenient
        // 1번째(이중 실행 방지 가드)·2번째(락 안 재검사)=빈 목록, 3번째(최종 반환)=저장된 주문
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any()))
                .thenReturn(List.of(), List.of(), List.of(savedOrder));

        List<Order> orders = service.execute(STRATEGY.id(), REQUESTER_ID);

        verify(orderPort).saveAll(anyList());
        assertThat(orders).hasSize(1);
    }

    // 락 밖 이중 실행 검사 이후 같은 사이클에 배치가 먼저 저장했으면 락 안 재검사에서 거부 — 같은 사이클 중복 주문 방지
    @Test
    void execute_cycleOrderedWhileWaitingForLock_throwsAlreadyOrderedToday() {
        Order buyTemplate = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("20.00"),
                OrderStatus.PLANNED, null, null, null);
        Order batchSaved = new Order(UUID.randomUUID(), ACCOUNT.id(), CYCLE.id(), LocalDate.now(),
                StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("20.00"),
                OrderStatus.PLANNED, null, null, null);
        lenient().when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate.toPlanned()));
        // 1번째(락 밖 가드)=빈 목록, 2번째(락 안 재검사)=배치가 그 사이 저장한 주문
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any()))
                .thenReturn(List.of(), List.of(batchSaved));

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(AlreadyOrderedTodayException.class);
        verify(orderPort, never()).saveAll(anyList());
    }

    // 같은 계좌의 배치 승인·재캡과 live 여유분을 이중 사용하지 않도록 allocator 승인~PLANNED 저장이 계좌 예산 락 안에서 끝나야 한다
    @Test
    void execute_allocationAndSave_runInsideAccountBudgetLock() throws InterruptedException {
        Order buyTemplate = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("20.00"),
                OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate.toPlanned()));
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        AtomicBoolean held = trackLockHeld(budgetLock);
        AtomicBoolean reservedReadInLock = new AtomicBoolean();
        AtomicBoolean savedInLock = new AtomicBoolean();
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenAnswer(invocation -> {
            reservedReadInLock.set(held.get());
            return BigDecimal.ZERO;
        });
        doAnswer(invocation -> {
            savedInLock.set(held.get());
            return null;
        }).when(orderPort).saveAll(anyList());

        service.execute(STRATEGY.id(), REQUESTER_ID);

        verify(budgetLock).call(eq(ACCOUNT.id()), any());
        assertThat(reservedReadInLock).isTrue();
        assertThat(savedInLock).isTrue();
    }

    // 캡 적용 전 금액으로는 예수금 부족이지만 캡 적용 후 금액으로는 충분한 경계 케이스 —
    // 수동실행이 배치와 동일하게 "캡 적용 후" 금액으로 검증하도록 바뀐 것을 고정하는 회귀 테스트
    @Test
    void execute_buyPriceExceedsCapButCappedAmountFitsBudget_savesOrders() {
        // 원가 100.00×1주=100.00은 live usdDeposit(60.00)을 초과하지만,
        // 현재가 50.00 기준 캡(52.50)으로 보정된 뒤 금액(52.50)은 예산 안에 든다
        Order buyTemplate = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("100.00"),
                OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate.toPlanned()));
        // InfinitePosition 캡 재산정 — 1주 그대로, 가격만 cap(52.50)으로 교체
        when(infiniteStrategy.buildCappedBuyOrders(any(InfinitePosition.class), any(LocalDate.class), anyList(), eq(new BigDecimal("52.50"))))
                .thenAnswer(invocation -> {
                    List<PlannedOrder> original = invocation.getArgument(2);
                    return original.stream().map(o -> o.withPrice(new BigDecimal("52.50"))).toList();
                });
        // 캡 판단용 현재가 50.00 → cap = 52.50
        when(kisPricePort.getPrices(eq(List.of(StrategyTicker.SOXL)), eq(ACCOUNT_REF)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("50.00")));
        // live 잔고: holdings=10, usdDeposit=60.00 — 캡 전(100.00)은 부족, 캡 후(52.50)는 충분
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(10, new BigDecimal("20.00"), new BigDecimal("60.00")));
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(BigDecimal.ZERO);
        Order savedOrder = new Order(UUID.randomUUID(), ACCOUNT.id(), CYCLE.id(), LocalDate.now(),
                StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("52.50"), OrderStatus.PLANNED, null, null, null);
        lenient().when(orderPort.findPlannedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of());
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any()))
                .thenReturn(List.of(), List.of(), List.of(savedOrder));

        List<Order> orders = service.execute(STRATEGY.id(), REQUESTER_ID);

        verify(orderPort).saveAll(argThat(saved -> saved.stream()
                .anyMatch(o -> o.price().compareTo(new BigDecimal("52.50")) == 0)));
        assertThat(orders).hasSize(1);
    }

    // 캡을 적용해도 여전히 예산을 초과하면 거부되어야 한다(캡이 항상 통과시키는 것은 아님을 확인)
    @Test
    void execute_cappedAmountStillExceedsBudget_throwsManualTradingException() {
        Order buyTemplate = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("100.00"),
                OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate.toPlanned()));
        when(infiniteStrategy.buildCappedBuyOrders(any(InfinitePosition.class), any(LocalDate.class), anyList(), eq(new BigDecimal("52.50"))))
                .thenAnswer(invocation -> {
                    List<PlannedOrder> original = invocation.getArgument(2);
                    return original.stream().map(o -> o.withPrice(new BigDecimal("52.50"))).toList();
                });
        when(kisPricePort.getPrices(eq(List.of(StrategyTicker.SOXL)), eq(ACCOUNT_REF)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("50.00")));
        // live usdDeposit=10.00 — 캡 후 금액(52.50)조차 초과
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(10, new BigDecimal("20.00"), new BigDecimal("10.00")));
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingException.class)
                .hasMessage("예수금이 부족합니다");

        verify(orderPort, never()).saveAll(anyList());
    }

    // NO_CYCLE_HISTORY skip은 planBuilder.build()가 내부적으로 tryLoadBalance(soft skip)를 쓰면서
    // 조용한 List.of() 무동작으로 바뀌었던 리뷰 발견 결함을 고정하는 회귀 테스트 —
    // 이력 없는 전략은 사용자가 원인을 알 수 없는 무동작이 아니라 명시적 4xx로 거부돼야 한다
    @Test
    void execute_noCycleHistory_throwsManualTradingException() {
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingException.class)
                .hasMessage("전략 실행 이력이 없어 수동 실행할 수 없습니다");

        verify(orderPort, never()).saveAll(anyList());
    }

    // 점검 이슈 PRIVACY 기준표는 바로 주문도 시끄럽게 거부 — 주문 저장 없음
    @Test
    void execute_privacyBaseBlocked_throwsManualTradingException() {
        Strategy privacy = new Strategy(UUID.randomUUID(), ACCOUNT.id(), StrategyType.PRIVACY,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle privacyCycle = new StrategyCycle(UUID.randomUUID(), privacy.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        PrivacyTradeBase base = new PrivacyTradeBase(
                UUID.randomUUID(), new BigDecimal("20.00"), 10, new BigDecimal("20.00"), List.of());
        when(strategyPort.findByIdOrThrow(privacy.id())).thenReturn(privacy);
        when(strategyCyclePort.requireLatestByStrategyId(privacy.id())).thenReturn(privacyCycle);
        when(cyclePositionPort.findLatestOneByStrategyId(privacy.id())).thenReturn(Optional.of(
                new CyclePosition(null, privacyCycle.id(), new BigDecimal("1000.00"), new BigDecimal("22.00"),
                        new BigDecimal("20.00"), 10, null, null)));
        when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.of(base));
        when(privacyBaseGuard.usable(base)).thenReturn(false);

        assertThatThrownBy(() -> service.execute(privacy.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingException.class)
                .hasMessage("P 매매표 점검에서 이상이 발견돼 오늘은 PRIVACY 주문을 낼 수 없습니다.");

        verify(orderPort, never()).saveAll(anyList());
    }

    // VR 수동 실행 공용 테스트 픽스처 — 개장 전/후 분기를 나누는 두 테스트가 공유
    private record VrFixture(Strategy vrStrat, StrategyCycle vrCycle, UUID vrVersionId,
                              Order vrBuyPlanned, Order vrSellPlanned) {}

    private VrFixture setUpVrManualExecution() {
        Strategy vrStrat = new Strategy(UUID.randomUUID(), ACCOUNT.id(), StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        UUID vrVersionId = UUID.randomUUID();
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vrStrat.id(), vrVersionId,
                new BigDecimal("5000.00"), null, LocalDate.now(), null, null, null);
        // VR 잔고 이력 — holdings=5
        CyclePosition vrHistory = new CyclePosition(
                null, vrCycle.id(), new BigDecimal("5000.00"), new BigDecimal("22.00"),
                new BigDecimal("20.00"), 5, null, null);
        CyclePosition vrOpening = CyclePosition.cycleStartSnapshot(
                vrCycle.id(), new BigDecimal("5000.00"), new BigDecimal("22.00"));

        // VR 사이클·버전 상세
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                vrCycle.id(), new BigDecimal("1000.00"), 10, new BigDecimal("2500.00"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(vrVersionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));

        // VR buildOrders 결과: LIMIT + AT_OPEN 주문 (BUY 1주 + SELL 1주)
        Order vrBuyTemplate = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.BUY,
                1, new BigDecimal("22.00"), OrderStatus.PLANNED, null, null, null);
        Order vrSellTemplate = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL,
                1, new BigDecimal("25.00"), OrderStatus.PLANNED, null, null, null);
        UUID vrBuyId = UUID.randomUUID();
        UUID vrSellId = UUID.randomUUID();
        Order vrBuyPlanned = new Order(vrBuyId, ACCOUNT.id(), vrCycle.id(), LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.BUY,
                1, new BigDecimal("22.00"), OrderStatus.PLANNED, null, null, null);
        Order vrSellPlanned = new Order(vrSellId, ACCOUNT.id(), vrCycle.id(), LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL,
                1, new BigDecimal("25.00"), OrderStatus.PLANNED, null, null, null);

        when(strategyPort.findByIdOrThrow(vrStrat.id())).thenReturn(vrStrat);
        when(strategyCyclePort.requireLatestByStrategyId(vrStrat.id())).thenReturn(vrCycle);
        // 1번째(이중 실행 방지 가드)·2번째(락 안 재검사)=빈 목록, 3번째(최종 반환)=저장된 주문
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(vrCycle.id()), any()))
                .thenReturn(List.of(), List.of(), List.of(vrBuyPlanned, vrSellPlanned));
        // 잔고: cycle_position 이력에서 로드
        when(cyclePositionPort.findLatestOneByStrategyId(vrStrat.id())).thenReturn(Optional.of(vrHistory));
        when(cyclePositionPort.findFirstOne(vrCycle.id())).thenReturn(Optional.of(vrOpening));
        // VR 전용 포트 — CycleOrderComputer VrInputs 조립
        when(strategyCycleVrPort.findByCycleId(vrCycle.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(vrVersionId)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(vrCycle.id())).thenReturn(BigDecimal.ZERO);
        // buildOrders: LIMIT + AT_OPEN 주문 반환 — 수동실행은 currentPrice=null 전달하지만
        // setUp()의 전역 kisPricePort 스텁이 SOXL 전일종가 20.00을 반환 → referencePrice=20.00(대체), currentPrice(live)=null
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(StrategyTicker.SOXL), eq(new BigDecimal("20.00")), any()))
                .thenReturn(List.of(vrBuyTemplate.toPlanned(), vrSellTemplate.toPlanned()));
        // live 잔고 검증 — BUY $22 << usdDeposit $10,000
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(5, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(BigDecimal.ZERO);

        return new VrFixture(vrStrat, vrCycle, vrVersionId, vrBuyPlanned, vrSellPlanned);
    }

    @Test
    void execute_vrStrategy_savesLimitAtOpenOrders() {
        // VR 전략 수동 실행 — LIMIT + AT_OPEN 주문이 저장되는지만 검증 (AT_OPEN 즉시 접수 분기는 별도 테스트)
        VrFixture fx = setUpVrManualExecution();
        lenient().when(orderPort.findAtOpenPlannedByCycleAndDate(eq(fx.vrCycle().id()), any()))
                .thenReturn(List.of(fx.vrBuyPlanned(), fx.vrSellPlanned()));

        List<Order> result = service.execute(fx.vrStrat().id(), REQUESTER_ID);

        // VR 전용 포트 호출 검증
        verify(strategyCycleVrPort).findByCycleId(fx.vrCycle().id());
        verify(strategyVrDetailPort).findByStrategyVersionId(fx.vrVersionId());
        verify(orderPort).sumFilledBuyAmountByCycleId(fx.vrCycle().id());
        // LIMIT + AT_OPEN 주문이 저장됨
        verify(orderPort).saveAll(argThat(orders -> orders.stream().allMatch(o ->
                o.orderType() == OrderType.LIMIT && o.timing() == OrderTiming.AT_OPEN)));
        // 최종 반환 주문 확인
        assertThat(result).hasSize(2);
    }

    @Test
    void execute_vrStrategy_marketOpen_placesAtOpenOrdersWithCorrectArguments() {
        // 개장 후 수동 실행 — DstInfo.immediateOpen()으로 marketOpen을 과거로 고정해
        // placeAtOpenOrdersIfMarketOpen의 개장 분기를 결정론적으로 강제한다 (실시간 시각 의존 제거)
        VrFixture fx = setUpVrManualExecution();
        when(orderPort.findAtOpenPlannedByCycleAndDate(eq(fx.vrCycle().id()), any()))
                .thenReturn(List.of(fx.vrBuyPlanned(), fx.vrSellPlanned()));
        // BUY cap 판단용 최신 현재가 재조회 — placeAtOpenOrdersIfMarketOpen 내부에서 fetchPrices 호출
        when(kisPricePort.getPrices(eq(List.of(StrategyTicker.SOXL)), eq(ACCOUNT_REF)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("21.00")));

        List<Order> result = service.execute(fx.vrStrat().id(), REQUESTER_ID, DstInfo.immediateOpen());

        assertThat(result).hasSize(2);
        // VR 수동실행 plan.position()은 항상 null(VrCycleOrderStrategy.plan()), vrPosition은 non-null,
        // currentPrice는 위에서 재조회한 21.00, cycleId/account/strategy도 실제 값과 일치해야 한다
        verify(orderExecutor, times(1)).placeAtOpenOrders(
                any(LocalDate.class), eq(TradingAccount.from(ACCOUNT)), eq(fx.vrCycle().id()),
                eq(new BigDecimal("21.00")), isNull(), any(VrPosition.class), eq(fx.vrStrat()));
    }

    @Test
    void execute_vrStrategy_marketClosed_doesNotPlaceAtOpenOrders() {
        // 개장 전 수동 실행 — marketOpen을 미래로 설정해 개장 분기를 결정론적으로 회피한다
        VrFixture fx = setUpVrManualExecution();
        Instant future = Instant.now().plusSeconds(3600);
        DstInfo marketClosed = new DstInfo(false, future, future, future);

        List<Order> result = service.execute(fx.vrStrat().id(), REQUESTER_ID, marketClosed);

        assertThat(result).hasSize(2);
        // 개장 전이므로 AT_OPEN 즉시 접수가 호출되지 않아야 함 — 개장 스케쥴러가 담당
        verify(orderExecutor, never()).placeAtOpenOrders(any(), any(), any(), any(), any(), any(), any());
    }

    // 계좌 예산 락 구간 안에서만 true — 승인분 PLANNED 저장이 락 안에서 커밋되는지 검증용
    private static AtomicBoolean trackLockHeld(AccountBudgetLock lock) throws InterruptedException {
        AtomicBoolean held = new AtomicBoolean();
        doAnswer(invocation -> {
            held.set(true);
            try {
                return invocation.callRealMethod();
            } finally {
                held.set(false);
            }
        }).when(lock).call(any(), any());
        return held;
    }
}
