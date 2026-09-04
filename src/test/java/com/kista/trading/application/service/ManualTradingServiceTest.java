package com.kista.trading.application.service;

import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.SellableQuantity;
import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.ManualTradingException;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.StrategyRef; import com.kista.trading.domain.model.*;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.user.domain.model.User;
import com.kista.user.application.port.output.UserPort;
import com.kista.privacy.application.port.output.PrivacyTradePort; import com.kista.trading.application.port.output.*;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.broker.domain.model.PriceSnapshot;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.broker.application.port.output.LiveBalancePort;
import com.kista.broker.application.port.output.SellableQuantityPort;
import com.kista.trading.application.port.output.StrategyCycleVrPort;
import com.kista.trading.application.port.output.StrategyVrDetailPort;
import com.kista.trading.domain.strategy.*;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.support.DomainFixtures;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.kista.sharedkernel.NotificationChannel;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
class ManualTradingServiceTest {

    @Mock StrategyLookupPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock AccountPort accountPort;
    @Mock OrderPort orderPort;
    @Mock UserPort userPort;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock BrokerPricePort kisPricePort;      // BrokerPricePort 직접 mock (KisPricePort 삭제됨)
    @Mock CyclePositionPort cyclePositionPort;
    @Mock CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    @Mock StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    @Mock LiveBalancePort liveBalancePort;   // LiveBalancePort 직접 mock
    @Mock com.kista.broker.application.service.BrokerAdapterRegistry brokerAdapterRegistry;
    @Mock SellableQuantityPort sellableQuantityPort;
    @Mock TradingOrderExecutor orderExecutor;
    @Mock InfiniteStrategy infiniteStrategy; // class-level — 테스트별로 stub 가능
    @Mock StrategyCycleVrPort strategyCycleVrPort; // CycleOrderComputer VR 분기용
    @Mock StrategyVrDetailPort strategyVrDetailPort; // CycleOrderComputer VR 분기용
    @Mock VrStrategy vrStrategy; // VrCycleOrderStrategy 조립용
    @Mock ApplicationEventPublisher eventPublisher; // 4xx 예외라 GlobalExceptionHandler가 저장 안 하는 외부 API 실패를 직접 기록

    ManualTradingService service;

    static final UUID REQUESTER_ID = UUID.randomUUID();
    static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), REQUESTER_ID);
    static final BrokerAccountRef ACCOUNT_REF = ACCOUNT.toBrokerRef();
    static final StrategyRef STRATEGY = new StrategyRef(
            UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
            StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE
    );
    static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();
    static final StrategyCycle CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), STRATEGY_VERSION_ID, new BigDecimal("1000.00"), null,
            LocalDate.now(), null, null, null
    );
    static final User USER = DomainFixtures.activeUser(REQUESTER_ID, NotificationChannel.NONE);
    // DB 잔고 이력 — cycle_position 기반 (TradingBalanceLoader가 읽음)
    static final CyclePosition HISTORY = new CyclePosition(
            null, CYCLE.id(), new BigDecimal("1000.00"), new BigDecimal("22.00"),
            new BigDecimal("20.00"), 10, null, null
    );

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

        // BrokerPricePort: kisPricePort 직접 연결 (KisPricePort 삭제로 단순화)
        doReturn(kisPricePort).when(brokerAdapterRegistry).require(any(BrokerAccountRef.class), eq(BrokerPricePort.class));

        // LiveBalancePort: 필드 mock 직접 연결
        doReturn(liveBalancePort).when(brokerAdapterRegistry).require(any(BrokerAccountRef.class), eq(LiveBalancePort.class));

        TradingPriceFetcher priceFetcher = new TradingPriceFetcher(brokerAdapterRegistry, eventPublisher);
        service = new ManualTradingService(
                strategyPort, strategyCyclePort, accountPort, orderPort,
                userPort, privacyTradePort, priceFetcher, balanceLoader,
                orderComputer, orderPlanner, orderExecutor, brokerAdapterRegistry, eventPublisher);

        // getSellableQuantity 기본 stub — BUY 전용 테스트에서 SELL 체크가 0>충분값으로 통과
        lenient().when(brokerAdapterRegistry.require(any(), eq(SellableQuantityPort.class)))
                .thenReturn(sellableQuantityPort);
        lenient().when(sellableQuantityPort.getSellableQuantity(any(), any()))
                .thenReturn(new SellableQuantity("SOXL", 100));

        // 공통 stubbing — lenient: VR 전략 테스트에서 vrStrat.id()를 사용하므로 STRATEGY.id() stub은 미호출 가능
        lenient().when(strategyPort.findByIdOrThrow(STRATEGY.id())).thenReturn(STRATEGY);
        // requireOwnedAccount는 default 메서드 — mock이 override하므로 직접 stub
        when(accountPort.requireOwnedAccount(ACCOUNT.id(), REQUESTER_ID)).thenReturn(ACCOUNT);
        lenient().when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(CYCLE));
        lenient().when(userPort.findByIdOrThrow(ACCOUNT.userId())).thenReturn(USER);
        lenient().when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of());
        lenient().when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(HISTORY));
        lenient().when(cyclePositionPort.findLatestByCycleId(eq(CYCLE.id()), anyInt())).thenReturn(List.of(HISTORY));
        lenient().when(cyclePositionInfiniteDetailPort.findLatestByCycleId(eq(CYCLE.id()), anyInt())).thenReturn(List.of());
        lenient().when(strategyInfiniteDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 40)));
        lenient().when(strategyInfiniteDetailPort.findActiveByStrategyId(STRATEGY.id()))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 40)));
        lenient().when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT_REF)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new PriceSnapshot(new BigDecimal("22.00"), new BigDecimal("20.00"))));
    }

    @Test
    void execute_insufficientSellHoldings_throwsManualTradingException() {
        // SELL 15주 계획, live holdings=10 → 보유수량 부족 → ManualTradingException
        Order sellOrder = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_OPEN,
                Order.OrderDirection.SELL, 15, new BigDecimal("22.00"),
                Order.OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(sellOrder));
        // live holdings=10, sellable=10 < SELL 15주
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        when(sellableQuantityPort.getSellableQuantity(any(), any()))
                .thenReturn(new SellableQuantity("SOXL", 10));

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingException.class)
                .hasMessageContaining("보유 수량이 부족합니다");
    }

    @Test
    void execute_liveBalanceFetchFails_notifiesAdminAndThrowsManualTradingException() {
        // 브로커 API 실패는 4xx(ManualTradingException)로 승격되지만, GlobalExceptionHandler가
        // 4xx는 app_error_logs에 남기지 않으므로 서비스가 직접 TradingErrorEvent를 발행해야 함
        Order buyOrder = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_OPEN,
                Order.OrderDirection.BUY, 1, new BigDecimal("22.00"),
                Order.OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyOrder));
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenThrow(new RuntimeException("Toss API 오류"));

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingException.class);

        verify(eventPublisher).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    void execute_existingReservedSellExceedsAvailable_rejects() {
        Order sellOrder = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_OPEN,
                Order.OrderDirection.SELL, 3, new BigDecimal("22.00"),
                Order.OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(sellOrder));
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(5, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        when(sellableQuantityPort.getSellableQuantity(any(), any()))
                .thenReturn(new SellableQuantity("SOXL", 5));
        when(orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                eq(ACCOUNT.id()), any(LocalDate.class), eq(StrategyTicker.SOXL)))
                .thenReturn(3);

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingException.class)
                .hasMessage("보유 수량이 부족합니다");

        verify(orderPort, never()).saveAll(anyList());
    }

    @Test
    void execute_sufficientBalance_savesOrders() {
        // BUY 1주, live 충분(usdDeposit=$10,000, holdings=10) → saveAll 호출, 주문 반환
        Order buyTemplate = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE,
                Order.OrderDirection.BUY, 1, new BigDecimal("20.00"),
                Order.OrderStatus.PLANNED, null, null, null);
        Order savedOrder = new Order(UUID.randomUUID(), ACCOUNT.id(), CYCLE.id(), LocalDate.now(),
                StrategyTicker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE,
                Order.OrderDirection.BUY, 1, new BigDecimal("20.00"),
                Order.OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate));
        // live 잔고 충분: usdDeposit=$10,000 > BUY $20
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(orderPort.findPlannedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of()); // AT_OPEN 없음(BUY뿐) — 개장 후에만 호출되므로 lenient
        // 1번째 호출(이중 실행 방지 가드)=빈 목록, 2번째 호출(최종 반환)=저장된 주문
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any()))
                .thenReturn(List.of(), List.of(savedOrder));

        List<Order> orders = service.execute(STRATEGY.id(), REQUESTER_ID);

        verify(orderPort).saveAll(anyList());
        assertThat(orders).hasSize(1);
    }

    // VR 수동 실행 공용 테스트 픽스처 — 개장 전/후 분기를 나누는 두 테스트가 공유
    private record VrFixture(StrategyRef vrStrat, StrategyCycle vrCycle, UUID vrVersionId,
                              Order vrBuyPlanned, Order vrSellPlanned) {}

    private VrFixture setUpVrManualExecution() {
        StrategyRef vrStrat = new StrategyRef(UUID.randomUUID(), ACCOUNT.id(), StrategyType.VR,
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
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.BUY,
                1, new BigDecimal("22.00"), Order.OrderStatus.PLANNED, null, null, null);
        Order vrSellTemplate = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL,
                1, new BigDecimal("25.00"), Order.OrderStatus.PLANNED, null, null, null);
        UUID vrBuyId = UUID.randomUUID();
        UUID vrSellId = UUID.randomUUID();
        Order vrBuyPlanned = new Order(vrBuyId, ACCOUNT.id(), vrCycle.id(), LocalDate.now(), StrategyTicker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.BUY,
                1, new BigDecimal("22.00"), Order.OrderStatus.PLANNED, null, null, null);
        Order vrSellPlanned = new Order(vrSellId, ACCOUNT.id(), vrCycle.id(), LocalDate.now(), StrategyTicker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL,
                1, new BigDecimal("25.00"), Order.OrderStatus.PLANNED, null, null, null);

        when(strategyPort.findByIdOrThrow(vrStrat.id())).thenReturn(vrStrat);
        when(strategyCyclePort.findLatestByStrategyId(vrStrat.id())).thenReturn(Optional.of(vrCycle));
        // 1번째 호출: 이중 실행 방지 가드 → 빈 목록, 2번째 호출: 최종 반환 → 저장된 주문
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(vrCycle.id()), any()))
                .thenReturn(List.of(), List.of(vrBuyPlanned, vrSellPlanned));
        // 잔고: cycle_position 이력에서 로드
        when(cyclePositionPort.findLatestOneByStrategyId(vrStrat.id())).thenReturn(Optional.of(vrHistory));
        when(cyclePositionPort.findFirstOne(vrCycle.id())).thenReturn(Optional.of(vrOpening));
        // VR 전용 포트 — CycleOrderComputer VrInputs 조립
        when(strategyCycleVrPort.findByCycleId(vrCycle.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(vrVersionId)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(vrCycle.id())).thenReturn(BigDecimal.ZERO);
        // buildOrders: LIMIT + AT_OPEN 주문 반환 — 수동실행은 currentPrice=null 전달하지만
        // setUp()의 전역 kisPricePort 스텁이 SOXL 전일종가 20.00을 반환 → referencePrice=20.00(대체), currentPrice(live)=null
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(StrategyTicker.SOXL), eq(new BigDecimal("20.00")), isNull(), any()))
                .thenReturn(List.of(vrBuyTemplate, vrSellTemplate));
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
                o.orderType() == Order.OrderType.LIMIT && o.timing() == Order.OrderTiming.AT_OPEN)));
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
                any(LocalDate.class), eq(ACCOUNT), eq(fx.vrCycle().id()),
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
}
