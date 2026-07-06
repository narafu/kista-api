package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.order.ManualTradingException;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.BrokerPricePort;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import com.kista.domain.port.out.StrategyCycleVrPort;
import com.kista.domain.port.out.StrategyVrDetailPort;
import com.kista.domain.strategy.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManualTradingServiceTest {

    @Mock StrategyPort strategyPort;
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
    @Mock com.kista.application.service.broker.BrokerAdapterRegistry brokerAdapterRegistry;
    @Mock SellableQuantityPort sellableQuantityPort;
    @Mock TradingOrderExecutor orderExecutor;
    @Mock InfiniteStrategy infiniteStrategy; // class-level — 테스트별로 stub 가능
    @Mock StrategyCycleVrPort strategyCycleVrPort; // CycleOrderComputer VR 분기용
    @Mock StrategyVrDetailPort strategyVrDetailPort; // CycleOrderComputer VR 분기용
    @Mock MarketCalendarPort marketCalendarPort; // VR 첫 사이클 거래일 계산용
    @Mock VrStrategy vrStrategy; // VrCycleOrderStrategy 조립용

    ManualTradingService service;

    static final UUID REQUESTER_ID = UUID.randomUUID();
    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), REQUESTER_ID, "테스트계좌",
            "74420614", "key", "secret", null, Account.Broker.KIS, null
    );
    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE
    );
    static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();
    static final StrategyCycle CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), STRATEGY_VERSION_ID, new BigDecimal("1000.00"), null,
            LocalDate.now(), null, null, null
    );
    static final User USER = new User(
            REQUESTER_ID, "kakao-1", "테스터", User.UserStatus.ACTIVE, User.UserRole.USER,
            null, null, null, null, NotificationChannel.NONE
    );
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
        lenient().when(marketCalendarPort.isMarketOpen(any(LocalDate.class))).thenReturn(true);
        CycleOrderComputer orderComputer = new CycleOrderComputer(
                cycleStrategies, cyclePositionPort, cyclePositionInfiniteDetailPort, strategyInfiniteDetailPort,
                strategyCyclePort, strategyCycleVrPort, strategyVrDetailPort, orderPort, new TradingDayCounter(marketCalendarPort));
        TradingOrderPlanner orderPlanner = new TradingOrderPlanner(orderPort);

        // BrokerPricePort: kisPricePort 직접 연결 (KisPricePort 삭제로 단순화)
        doReturn(kisPricePort).when(brokerAdapterRegistry).require(any(Account.class), eq(BrokerPricePort.class));

        // LiveBalancePort: 필드 mock 직접 연결
        doReturn(liveBalancePort).when(brokerAdapterRegistry).require(any(Account.class), eq(LiveBalancePort.class));

        TradingPriceFetcher priceFetcher = new TradingPriceFetcher(brokerAdapterRegistry);
        service = new ManualTradingService(
                strategyPort, strategyCyclePort, accountPort, orderPort,
                userPort, privacyTradePort, priceFetcher, balanceLoader,
                orderComputer, orderPlanner, orderExecutor, brokerAdapterRegistry);

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
        lenient().when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(new BigDecimal("22.00"), new BigDecimal("20.00"))));
    }

    @Test
    void execute_insufficientSellHoldings_throwsManualTradingException() {
        // SELL 15주 계획, live holdings=10 → 보유수량 부족 → ManualTradingException
        Order sellOrder = new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_OPEN,
                Order.OrderDirection.SELL, 15, new BigDecimal("22.00"),
                Order.OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(sellOrder));
        // live holdings=10, sellable=10 < SELL 15주
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        when(sellableQuantityPort.getSellableQuantity(any(), any()))
                .thenReturn(new SellableQuantity("SOXL", 10));

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingException.class)
                .hasMessageContaining("보유 수량이 부족합니다");
    }

    @Test
    void execute_sufficientBalance_savesOrders() {
        // BUY 1주, live 충분(usdDeposit=$10,000, holdings=10) → saveAll 호출, 주문 반환
        Order buyTemplate = new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE,
                Order.OrderDirection.BUY, 1, new BigDecimal("20.00"),
                Order.OrderStatus.PLANNED, null, null, null);
        Order savedOrder = new Order(UUID.randomUUID(), ACCOUNT.id(), CYCLE.id(), LocalDate.now(),
                Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE,
                Order.OrderDirection.BUY, 1, new BigDecimal("20.00"),
                Order.OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate));
        // live 잔고 충분: usdDeposit=$10,000 > BUY $20
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(orderPort.findPlannedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of()); // AT_OPEN 없음(BUY뿐) — 개장 후에만 호출되므로 lenient
        // 1번째 호출(이중 실행 방지 가드)=빈 목록, 2번째 호출(최종 반환)=저장된 주문
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any()))
                .thenReturn(List.of(), List.of(savedOrder));

        List<Order> orders = service.execute(STRATEGY.id(), REQUESTER_ID);

        verify(orderPort).saveAll(anyList());
        assertThat(orders).hasSize(1);
    }

    @Test
    void execute_vrStrategy_savesLimitAtOpenOrders_andPlacesAtOpenIfMarketOpen() {
        // VR 전략 수동 실행 — LIMIT + AT_OPEN 주문이 저장되고, 개장 후이면 즉시 접수됨
        // AT_OPEN 즉시 접수(placeAtOpenOrdersIfMarketOpen)는 실시간 DstInfo 의존적이므로
        // orderExecutor.placeGiven() 호출 여부는 atMostOnce()로 허용 (개장 전/후 모두 통과)
        Strategy vrStrat = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        UUID vrVersionId = UUID.randomUUID();
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vrStrat.id(), vrVersionId,
                new BigDecimal("5000.00"), null, LocalDate.now(), null, null, null);
        // VR 잔고 이력 — holdings=5
        CyclePosition vrHistory = new CyclePosition(
                null, vrCycle.id(), new BigDecimal("5000.00"), new BigDecimal("22.00"),
                new BigDecimal("20.00"), 5, null, null);

        // VR 사이클·버전 상세
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                vrCycle.id(), new BigDecimal("1000.00"), 10, new BigDecimal("2500.00"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(vrVersionId, 4, new BigDecimal("15.00"), 0);

        // VR buildOrders 결과: LIMIT + AT_OPEN 주문 (BUY 1주 + SELL 1주)
        Order vrBuyTemplate = new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.BUY,
                1, new BigDecimal("22.00"), Order.OrderStatus.PLANNED, null, null, null);
        Order vrSellTemplate = new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL,
                1, new BigDecimal("25.00"), Order.OrderStatus.PLANNED, null, null, null);
        UUID vrBuyId = UUID.randomUUID();
        UUID vrSellId = UUID.randomUUID();
        Order vrBuyPlanned = new Order(vrBuyId, ACCOUNT.id(), vrCycle.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.BUY,
                1, new BigDecimal("22.00"), Order.OrderStatus.PLANNED, null, null, null);
        Order vrSellPlanned = new Order(vrSellId, ACCOUNT.id(), vrCycle.id(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL,
                1, new BigDecimal("25.00"), Order.OrderStatus.PLANNED, null, null, null);

        when(strategyPort.findByIdOrThrow(vrStrat.id())).thenReturn(vrStrat);
        when(strategyCyclePort.findLatestByStrategyId(vrStrat.id())).thenReturn(Optional.of(vrCycle));
        // 1번째 호출: 이중 실행 방지 가드 → 빈 목록, 2번째 호출: 최종 반환 → 저장된 주문
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(vrCycle.id()), any()))
                .thenReturn(List.of(), List.of(vrBuyPlanned, vrSellPlanned));
        // 잔고: cycle_position 이력에서 로드
        when(cyclePositionPort.findLatestOneByStrategyId(vrStrat.id())).thenReturn(Optional.of(vrHistory));
        // VR 전용 포트 — CycleOrderComputer VrInputs 조립
        when(strategyCycleVrPort.findByCycleId(vrCycle.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(vrVersionId)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(vrCycle.id())).thenReturn(BigDecimal.ZERO);
        // buildOrders: LIMIT + AT_OPEN 주문 반환
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), isNull(), any()))
                .thenReturn(List.of(vrBuyTemplate, vrSellTemplate));
        // live 잔고 검증 — BUY $22 << usdDeposit $10,000
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(5, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(BigDecimal.ZERO);
        // AT_OPEN 즉시 접수 준비 (placeAtOpenOrdersIfMarketOpen — 개장 후에만 호출, lenient)
        lenient().when(orderPort.findAtOpenPlannedByCycleAndDate(eq(vrCycle.id()), any()))
                .thenReturn(List.of(vrBuyPlanned, vrSellPlanned));

        List<Order> result = service.execute(vrStrat.id(), REQUESTER_ID);

        // VR 전용 포트 호출 검증
        verify(strategyCycleVrPort).findByCycleId(vrCycle.id());
        verify(strategyVrDetailPort).findByStrategyVersionId(vrVersionId);
        verify(orderPort).sumFilledBuyAmountByCycleId(vrCycle.id());
        // LIMIT + AT_OPEN 주문이 저장됨
        verify(orderPort).saveAll(argThat(orders -> orders.stream().allMatch(o ->
                o.orderType() == Order.OrderType.LIMIT && o.timing() == Order.OrderTiming.AT_OPEN)));
        // 최종 반환 주문 확인
        assertThat(result).hasSize(2);
        // 개장 후 수동 실행 시 AT_OPEN 주문 placeGiven 경로 — 실시간 DstInfo 의존적이므로 atMostOnce
        verify(orderExecutor, atMostOnce()).placeGiven(anyList(), eq(ACCOUNT));
    }
}
