package com.kista.broker.adapter.out.mock;

import com.kista.adapter.out.marketdata.CommonMarketPriceFeed;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.broker.domain.model.Execution;
import com.kista.broker.domain.model.MarginItem;
import com.kista.broker.domain.model.PresentBalanceResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 모의계좌 어댑터 — DB 스냅샷 기반 잔고·체결 시뮬레이션 검증
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.parallel.Execution(ExecutionMode.SAME_THREAD)
class MockBrokerAdapterTest {

    @Mock
    private CommonMarketPriceFeed priceFeed;

    @Mock
    private OrderPort orderPort;

    @Mock
    private StrategyPort strategyPort;

    @Mock
    private StrategyCyclePort strategyCyclePort;

    @Mock
    private CyclePositionPort cyclePositionPort;

    private MockBrokerAdapter adapter() {
        return new MockBrokerAdapter(priceFeed, orderPort, strategyPort, strategyCyclePort, cyclePositionPort);
    }

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final UUID CYCLE_ID = UUID.randomUUID();
    private static final Account ACCOUNT = new Account(ACCOUNT_ID, UUID.randomUUID(), "모의계좌",
            "12345678", "key", "secret", null, Account.Broker.MOCK, null);
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 25);
    private static final Strategy TQQQ_STRATEGY = new Strategy(STRATEGY_ID, ACCOUNT_ID, Strategy.Type.VR,
            Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
    private static final StrategyCycle TQQQ_CYCLE = new StrategyCycle(CYCLE_ID, STRATEGY_ID,
            new BigDecimal("1000.00"), null, TRADE_DATE, null, null, null);

    // getExecutions()가 strategy→cycle을 해석할 수 있도록 공통 stub — 개별 테스트는 findPlacedByCycleAndDate만 stub하면 된다
    private void stubTqqqCycle() {
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(TQQQ_STRATEGY));
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(TQQQ_CYCLE));
    }

    // 테스트용 PLACED 주문 생성 헬퍼
    private static Order placedOrder(Order.OrderType orderType, Order.OrderDirection direction,
                                      int quantity, BigDecimal price, String externalOrderId) {
        Order planned = Order.planned(TRADE_DATE, Ticker.TQQQ, orderType, direction, quantity, price);
        return planned.withPlaced(externalOrderId);
    }

    @Test
    @DisplayName("supports()는 MOCK을 반환한다")
    void supportsReturnsMock() {
        assertThat(adapter().supports()).isEqualTo(Account.Broker.MOCK);
    }

    // --- getExecutions() 체결 시뮬레이션 규칙표 ---

    @Test
    @DisplayName("MOC 주문은 지정가와 무관하게 항상 체결되며 체결가는 종가다")
    void mocOrderAlwaysFills() {
        stubTqqqCycle();
        Order order = placedOrder(Order.OrderType.MOC, Order.OrderDirection.BUY, 10,
                new BigDecimal("100.00"), "MOCK-1");
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("999.99"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        Execution execution = executions.get(0);
        assertThat(execution.price()).isEqualByComparingTo("999.99");
        assertThat(execution.quantity()).isEqualTo(10);
        assertThat(execution.direction()).isEqualTo(Order.OrderDirection.BUY);
        assertThat(execution.externalOrderId()).isEqualTo("MOCK-1");
    }

    @Test
    @DisplayName("LOC 매수는 종가<=지정가면 체결(체결가=종가), 종가>지정가면 미체결이다")
    void locBuyFillsWhenClosingPriceLteLimit() {
        stubTqqqCycle();
        Order fillable = placedOrder(Order.OrderType.LOC, Order.OrderDirection.BUY, 5,
                new BigDecimal("100.00"), "MOCK-2");
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(fillable));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("95.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).price()).isEqualByComparingTo("95.00");
    }

    @Test
    @DisplayName("LOC 매수는 종가>지정가면 미체결이다")
    void locBuyDoesNotFillWhenClosingPriceExceedsLimit() {
        stubTqqqCycle();
        Order unfillable = placedOrder(Order.OrderType.LOC, Order.OrderDirection.BUY, 5,
                new BigDecimal("100.00"), "MOCK-3");
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(unfillable));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("105.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("LOC 매도는 종가>=지정가면 체결(체결가=종가), 종가<지정가면 미체결이다")
    void locSellFillsWhenClosingPriceGteLimit() {
        stubTqqqCycle();
        Order fillable = placedOrder(Order.OrderType.LOC, Order.OrderDirection.SELL, 5,
                new BigDecimal("100.00"), "MOCK-4");
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(fillable));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("105.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).price()).isEqualByComparingTo("105.00");
    }

    @Test
    @DisplayName("LOC 매도는 종가<지정가면 미체결이다")
    void locSellDoesNotFillWhenClosingPriceBelowLimit() {
        stubTqqqCycle();
        Order unfillable = placedOrder(Order.OrderType.LOC, Order.OrderDirection.SELL, 5,
                new BigDecimal("100.00"), "MOCK-5");
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(unfillable));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("95.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("LIMIT 매수는 종가<=지정가면 체결하되 체결가는 지정가 그대로다")
    void limitBuyFillsAtOrderPriceNotClosingPrice() {
        stubTqqqCycle();
        Order order = placedOrder(Order.OrderType.LIMIT, Order.OrderDirection.BUY, 5,
                new BigDecimal("100.00"), "MOCK-6");
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("90.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        // LOC와 달리 체결가는 종가(90.00)가 아니라 지정가(100.00) 그대로
        assertThat(executions.get(0).price()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("LIMIT 매수는 종가>지정가면 미체결이다")
    void limitBuyDoesNotFillWhenClosingPriceExceedsLimit() {
        stubTqqqCycle();
        Order order = placedOrder(Order.OrderType.LIMIT, Order.OrderDirection.BUY, 5,
                new BigDecimal("100.00"), "MOCK-7");
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("110.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("LIMIT 매도는 종가>=지정가면 체결하되 체결가는 지정가 그대로다")
    void limitSellFillsAtOrderPriceNotClosingPrice() {
        stubTqqqCycle();
        Order order = placedOrder(Order.OrderType.LIMIT, Order.OrderDirection.SELL, 5,
                new BigDecimal("100.00"), "MOCK-8");
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("110.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).price()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("LIMIT 매도는 종가<지정가면 미체결이다")
    void limitSellDoesNotFillWhenClosingPriceBelowLimit() {
        stubTqqqCycle();
        Order order = placedOrder(Order.OrderType.LIMIT, Order.OrderDirection.SELL, 5,
                new BigDecimal("100.00"), "MOCK-9");
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("90.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("경계값 — 종가==지정가면 등호 포함 조건이므로 LOC 매수도 체결된다")
    void locBuyFillsWhenClosingPriceEqualsLimit() {
        stubTqqqCycle();
        Order order = placedOrder(Order.OrderType.LOC, Order.OrderDirection.BUY, 5,
                new BigDecimal("100.00"), "MOCK-10");
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("100.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).price()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("PLACED 주문이 없으면 빈 리스트를 반환하고 시세를 조회하지 않는다")
    void returnsEmptyWhenNoPlacedOrders() {
        stubTqqqCycle();
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of());

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("getExecutions는 cycleId로 스코프된 주문만 조회한다 — 다른 사이클 주문은 대상이 아니다")
    void getExecutionsScopesByCurrentActiveCycle() {
        stubTqqqCycle();
        // 활성 사이클(CYCLE_ID)만 stub — findPlacedByCycleAndDate가 다른 cycleId로 호출되면 stub 미스로 실패한다
        Order order = placedOrder(Order.OrderType.MOC, Order.OrderDirection.BUY, 3,
                new BigDecimal("100.00"), "MOCK-CYCLE-SCOPED");
        when(orderPort.findPlacedByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("100.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).externalOrderId()).isEqualTo("MOCK-CYCLE-SCOPED");
    }

    // --- place()/cancel() ---

    @Test
    @DisplayName("place()는 MOCK- 접두사 합성 externalOrderId를 부여한 PLACED 주문을 반환한다")
    void placeAssignsSyntheticOrderIdAndPlacedStatus() {
        Order planned = Order.planned(TRADE_DATE, Ticker.TQQQ, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 10, new BigDecimal("100.00"));

        Order result = adapter().place(planned, ACCOUNT);

        assertThat(result.status()).isEqualTo(Order.OrderStatus.PLACED);
        assertThat(result.externalOrderId()).startsWith("MOCK-");
    }

    @Test
    @DisplayName("cancel()은 예외 없이 아무 것도 하지 않는다")
    void cancelIsNoOp() {
        Order order = placedOrder(Order.OrderType.LOC, Order.OrderDirection.BUY, 10,
                new BigDecimal("100.00"), "MOCK-11");

        adapter().cancel(order, ACCOUNT);

        verifyNoInteractions(orderPort, strategyPort, strategyCyclePort, cyclePositionPort, priceFeed);
    }

    // --- getLiveBalance() ---

    @Test
    @DisplayName("getLiveBalance는 holdings/avgPrice는 해당 ticker 전략 값, usdDeposit은 계좌 전체 전략 합산 값을 반환한다")
    void getLiveBalanceSumsUsdDepositAcrossStrategiesButKeepsTickerSpecificHoldings() {
        Strategy soxlStrategy = new Strategy(UUID.randomUUID(), ACCOUNT_ID, Strategy.Type.PRIVACY,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(soxlStrategy, TQQQ_STRATEGY));

        CyclePosition soxlPosition = new CyclePosition(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("300.00"), new BigDecimal("40.00"), new BigDecimal("38.00"), 4, null, null);
        CyclePosition tqqqPosition = new CyclePosition(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("500.00"), new BigDecimal("50.00"), new BigDecimal("48.00"), 10, null, null);
        when(cyclePositionPort.findLatestOneByStrategyId(soxlStrategy.id())).thenReturn(Optional.of(soxlPosition));
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(tqqqPosition));

        AccountBalance balance = adapter().getLiveBalance(ACCOUNT, Ticker.TQQQ);

        // holdings/avgPrice는 TQQQ 전략 고유값
        assertThat(balance.holdings()).isEqualTo(10);
        assertThat(balance.avgPrice()).isEqualByComparingTo("48.00");
        // usdDeposit은 SOXL(300)+TQQQ(500) 계좌 전체 합산 — 다른 전략 예산으로 매수 오판정을 막기 위함
        assertThat(balance.usdDeposit()).isEqualByComparingTo("800.00");
    }

    @Test
    @DisplayName("getLiveBalance는 해당 ticker 전략이 없으면 IllegalStateException을 던진다")
    void getLiveBalanceThrowsWhenNoMatchingStrategy() {
        Strategy soxlStrategy = new Strategy(UUID.randomUUID(), ACCOUNT_ID, Strategy.Type.PRIVACY,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(soxlStrategy));

        MockBrokerAdapter adapter = adapter();
        assertThatThrownBy(() -> adapter.getLiveBalance(ACCOUNT, Ticker.TQQQ))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("getLiveBalance는 전략은 있지만 포지션 이력이 없으면 IllegalStateException을 던진다")
    void getLiveBalanceThrowsWhenNoPositionHistory() {
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(TQQQ_STRATEGY));
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());

        MockBrokerAdapter adapter = adapter();
        assertThatThrownBy(() -> adapter.getLiveBalance(ACCOUNT, Ticker.TQQQ))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- getSellableQuantity() ---

    @Test
    @DisplayName("getSellableQuantity는 최신 포지션의 holdings를 반환한다")
    void getSellableQuantityReturnsLatestHoldings() {
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(TQQQ_STRATEGY));
        CyclePosition position = new CyclePosition(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("500.00"), new BigDecimal("50.00"), new BigDecimal("48.00"), 7, null, null);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(position));

        SellableQuantity sellable = adapter().getSellableQuantity(Ticker.TQQQ, ACCOUNT);

        assertThat(sellable.symbol()).isEqualTo(Ticker.TQQQ.name());
        assertThat(sellable.quantity()).isEqualTo(7);
    }

    // --- getUsdBuyableAmount() ---

    @Test
    @DisplayName("getUsdBuyableAmount는 매우 큰 상수 값을 반환한다 (신규 전략 등록 게이트체크 전용)")
    void getUsdBuyableAmountReturnsLargeConstant() {
        BigDecimal result = adapter().getUsdBuyableAmount(ACCOUNT);

        assertThat(result).isGreaterThan(BigDecimal.valueOf(1_000_000));
    }

    // --- BrokerPricePort 위임 ---

    @Test
    @DisplayName("getPrice는 account와 무관하게 CommonMarketPriceFeed로 위임한다")
    void getPriceDelegatesToPriceFeed() {
        when(priceFeed.getPrice(Ticker.TQQQ)).thenReturn(new BigDecimal("123.45"));

        BigDecimal price = adapter().getPrice(Ticker.TQQQ, ACCOUNT);

        assertThat(price).isEqualByComparingTo("123.45");
    }

    @Test
    @DisplayName("getPrevClose는 account와 무관하게 CommonMarketPriceFeed로 위임한다")
    void getPrevCloseDelegatesToPriceFeed() {
        when(priceFeed.getPrevClose(Ticker.TQQQ)).thenReturn(new BigDecimal("120.00"));

        BigDecimal prevClose = adapter().getPrevClose(Ticker.TQQQ, ACCOUNT);

        assertThat(prevClose).isEqualByComparingTo("120.00");
    }

    // --- 스모크: getPresentBalance / getMargin ---

    @Test
    @DisplayName("getPresentBalance는 예외 없이 값을 채워 반환한다")
    void getPresentBalanceSmokeTest() {
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(TQQQ_STRATEGY));
        CyclePosition position = new CyclePosition(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("500.00"), new BigDecimal("50.00"), new BigDecimal("48.00"), 7, null, null);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(position));
        when(priceFeed.getPrice(Ticker.TQQQ)).thenReturn(new BigDecimal("55.00"));

        PresentBalanceResult result = adapter().getPresentBalance(ACCOUNT);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getMargin은 예외 없이 값을 채워 반환한다")
    void getMarginSmokeTest() {
        List<MarginItem> margin = adapter().getMargin(ACCOUNT);

        assertThat(margin).isNotEmpty();
        assertThat(margin.get(0).currency()).isEqualTo(com.kista.broker.domain.model.Currency.USD);
    }
}
