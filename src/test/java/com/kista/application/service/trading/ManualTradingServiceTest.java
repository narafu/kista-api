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
import com.kista.domain.port.out.broker.SellableQuantityPort;
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
    @Mock KisPricePort kisPricePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock KisAccountPort kisAccountPort;
    @Mock TosAccountPort tosAccountPort;
    @Mock com.kista.application.service.broker.BrokerAdapterRegistry brokerAdapterRegistry;
    @Mock SellableQuantityPort sellableQuantityPort;
    @Mock TradingOrderExecutor orderExecutor;
    @Mock InfiniteTradingStrategy infiniteStrategy; // class-level — 테스트별로 stub 가능

    ManualTradingService service;

    static final UUID REQUESTER_ID = UUID.randomUUID();
    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), REQUESTER_ID, "테스트계좌",
            "74420614", "key", "secret", null, Account.Broker.KIS, null
    );
    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE, 20
    );
    static final StrategyCycle CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), new BigDecimal("1000.00"), null,
            LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED
    );
    static final User USER = new User(
            REQUESTER_ID, "kakao-1", "테스터", User.UserStatus.ACTIVE, User.UserRole.USER,
            null, null, null, null, NotificationChannel.NONE
    );
    // DB 잔고 이력 — cycle_position 기반 (TradingBalanceLoader가 읽음)
    static final CyclePosition HISTORY = new CyclePosition(
            null, CYCLE.id(), new BigDecimal("1000.00"), new BigDecimal("22.00"),
            new BigDecimal("20.00"), 10, false, null, null
    );

    @BeforeEach
    void setUp() {
        // 실제 헬퍼 컴포넌트 조립 — TradingServiceTest 패턴 동일
        BrokerPriceRouter priceRouter = new BrokerPriceRouter(kisPricePort, null);
        TradingPriceFetcher priceFetcher = new TradingPriceFetcher(priceRouter);
        TradingBalanceLoader balanceLoader = new TradingBalanceLoader(cyclePositionPort);
        BrokerAccountRouter brokerAccountRouter = new BrokerAccountRouter(kisAccountPort, tosAccountPort, brokerAdapterRegistry);
        ReverseInfiniteTradingStrategy reverseStrategy = mock(ReverseInfiniteTradingStrategy.class);
        PrivacyTradingStrategy privacyStrategy = mock(PrivacyTradingStrategy.class);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy),
                new PrivacyCycleOrderStrategy(privacyStrategy)));
        CycleOrderComputer orderComputer = new CycleOrderComputer(cycleStrategies, cyclePositionPort);
        TradingOrderPlanner orderPlanner = new TradingOrderPlanner(orderPort);

        service = new ManualTradingService(
                strategyPort, strategyCyclePort, accountPort, orderPort,
                userPort, privacyTradePort, priceFetcher, balanceLoader,
                orderComputer, orderPlanner, orderExecutor, brokerAccountRouter);

        // getSellableQuantity 기본 stub — BUY 전용 테스트에서 SELL 체크가 0>충분값으로 통과
        lenient().when(brokerAdapterRegistry.require(any(), eq(SellableQuantityPort.class)))
                .thenReturn(sellableQuantityPort);
        lenient().when(sellableQuantityPort.getSellableQuantity(any(), any()))
                .thenReturn(new SellableQuantity("SOXL", 100));

        // 공통 stubbing
        when(strategyPort.findByIdOrThrow(STRATEGY.id())).thenReturn(STRATEGY);
        // requireOwnedAccount는 default 메서드 — mock이 override하므로 직접 stub
        when(accountPort.requireOwnedAccount(ACCOUNT.id(), REQUESTER_ID)).thenReturn(ACCOUNT);
        lenient().when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(CYCLE));
        lenient().when(userPort.findByIdOrThrow(ACCOUNT.userId())).thenReturn(USER);
        lenient().when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of());
        lenient().when(cyclePositionPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(HISTORY));
        lenient().when(cyclePositionPort.findLatestByCycleId(eq(CYCLE.id()), anyInt())).thenReturn(List.of(HISTORY));
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
        when(kisAccountPort.getBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
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
        when(kisAccountPort.getBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(BigDecimal.ZERO);
        when(orderPort.findPlannedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of(savedOrder));

        List<Order> orders = service.execute(STRATEGY.id(), REQUESTER_ID);

        verify(orderPort).saveAll(anyList());
        assertThat(orders).hasSize(1);
    }
}
