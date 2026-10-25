package com.kista.tradingcore.broker.adapter.out.mock;

import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.broker.adapter.out.toss.TossPriceApi;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.broker.domain.model.SellableQuantity;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.broker.domain.model.Execution;
import com.kista.tradingcore.broker.domain.model.MarginItem;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.broker.domain.model.PlacedOrderView;
import com.kista.tradingcore.broker.domain.model.PositionView;
import com.kista.tradingcore.broker.domain.model.PresentBalanceResult;
import com.kista.tradingcore.broker.domain.model.StrategyRefLite;
import com.kista.tradingcore.broker.application.port.output.MockSimulationDataPort;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
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
    private TossPriceApi priceFeed;

    @Mock
    private MockSimulationDataPort mockSimulationDataPort;

    private MockBrokerAdapter adapter() {
        return new MockBrokerAdapter(priceFeed, mockSimulationDataPort);
    }

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final UUID CYCLE_ID = UUID.randomUUID();
    private static final BrokerAccountRef ACCOUNT = new BrokerAccountRef(ACCOUNT_ID, "key", "secret",
            "12345678", null, Broker.MOCK);
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 25);
    private static final StrategyRefLite TQQQ_STRATEGY = new StrategyRefLite(STRATEGY_ID, StrategyTicker.TQQQ);

    // getExecutions()가 strategy→cycle을 해석할 수 있도록 공통 stub — 개별 테스트는 findPlacedOrders만 stub하면 된다
    private void stubTqqqCycle() {
        when(mockSimulationDataPort.findStrategiesByAccountId(ACCOUNT_ID)).thenReturn(List.of(TQQQ_STRATEGY));
        when(mockSimulationDataPort.findActiveCycleId(STRATEGY_ID)).thenReturn(CYCLE_ID);
    }

    // 거래일 일봉 stub — 인자 순서: open, high, low, close
    private void stubCandle(String open, String high, String low, String close) {
        when(priceFeed.fetchConfirmedCandle(eq(StrategyTicker.TQQQ), any())).thenReturn(Optional.of(new BrokerCandle(
                TRADE_DATE, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), 1L)));
    }

    // 테스트용 PLACED 주문 뷰 생성 헬퍼
    private static PlacedOrderView placedOrder(OrderType orderType, OrderDirection direction,
                                                int quantity, BigDecimal price, String externalOrderId) {
        return new PlacedOrderView(direction, orderType, quantity, price, externalOrderId);
    }

    @Test
    @DisplayName("supports()는 MOCK을 반환한다")
    void supportsReturnsMock() {
        assertThat(adapter().supports()).isEqualTo(Broker.MOCK);
    }

    // --- getExecutions() 체결 시뮬레이션 규칙표 ---

    @Test
    @DisplayName("MOC 주문은 지정가와 무관하게 항상 체결되며 체결가는 종가다")
    void mocOrderAlwaysFills() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.MOC, OrderDirection.BUY, 10,
                new BigDecimal("100.00"), "MOCK-1");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        stubCandle("999.99", "999.99", "999.99", "999.99");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        Execution execution = executions.getFirst();
        assertThat(execution.price()).isEqualByComparingTo("999.99");
        assertThat(execution.quantity()).isEqualTo(10);
        assertThat(execution.direction()).isEqualTo(OrderDirection.BUY);
        assertThat(execution.externalOrderId()).isEqualTo("MOCK-1");
    }

    @Test
    @DisplayName("LOC 매수는 종가<=지정가면 체결(체결가=종가), 종가>지정가면 미체결이다")
    void locBuyFillsWhenClosingPriceLteLimit() {
        stubTqqqCycle();
        PlacedOrderView fillable = placedOrder(OrderType.LOC, OrderDirection.BUY, 5,
                new BigDecimal("100.00"), "MOCK-2");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(fillable));
        stubCandle("95.00", "95.00", "95.00", "95.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().price()).isEqualByComparingTo("95.00");
    }

    @Test
    @DisplayName("LOC 매수는 종가>지정가면 미체결이다")
    void locBuyDoesNotFillWhenClosingPriceExceedsLimit() {
        stubTqqqCycle();
        PlacedOrderView unfillable = placedOrder(OrderType.LOC, OrderDirection.BUY, 5,
                new BigDecimal("100.00"), "MOCK-3");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(unfillable));
        stubCandle("105.00", "105.00", "105.00", "105.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("LOC 매도는 종가>=지정가면 체결(체결가=종가), 종가<지정가면 미체결이다")
    void locSellFillsWhenClosingPriceGteLimit() {
        stubTqqqCycle();
        PlacedOrderView fillable = placedOrder(OrderType.LOC, OrderDirection.SELL, 5,
                new BigDecimal("100.00"), "MOCK-4");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(fillable));
        stubCandle("105.00", "105.00", "105.00", "105.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().price()).isEqualByComparingTo("105.00");
    }

    @Test
    @DisplayName("LOC 매도는 종가<지정가면 미체결이다")
    void locSellDoesNotFillWhenClosingPriceBelowLimit() {
        stubTqqqCycle();
        PlacedOrderView unfillable = placedOrder(OrderType.LOC, OrderDirection.SELL, 5,
                new BigDecimal("100.00"), "MOCK-5");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(unfillable));
        stubCandle("95.00", "95.00", "95.00", "95.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("LIMIT 매수 — 시가가 지정가보다 낮게 갭하락 개장하면 시가에 체결된다")
    void limitBuyGapDownFillsAtOpen() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.LIMIT, OrderDirection.BUY, 5,
                new BigDecimal("100.00"), "MOCK-6");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        stubCandle("90.00", "90.00", "90.00", "90.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().price()).isEqualByComparingTo("90.00"); // min(지정가 100, 시가 90)
    }

    @Test
    @DisplayName("LIMIT 매수는 종가>지정가면 미체결이다")
    void limitBuyDoesNotFillWhenClosingPriceExceedsLimit() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.LIMIT, OrderDirection.BUY, 5,
                new BigDecimal("100.00"), "MOCK-7");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        stubCandle("110.00", "110.00", "110.00", "110.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("LIMIT 매도 — 시가가 지정가보다 높게 갭상승 개장하면 시가에 체결된다")
    void limitSellGapUpFillsAtOpen() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.LIMIT, OrderDirection.SELL, 5,
                new BigDecimal("100.00"), "MOCK-8");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        stubCandle("110.00", "110.00", "110.00", "110.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().price()).isEqualByComparingTo("110.00");
    }

    @Test
    @DisplayName("LIMIT 매도는 종가<지정가면 미체결이다")
    void limitSellDoesNotFillWhenClosingPriceBelowLimit() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.LIMIT, OrderDirection.SELL, 5,
                new BigDecimal("100.00"), "MOCK-9");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        stubCandle("90.00", "90.00", "90.00", "90.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("LIMIT 매수 — 장중 저가가 지정가를 터치하면(종가는 지정가 위) 지정가에 체결된다")
    void limitBuyTouchedIntradayFillsAtLimit() {
        stubTqqqCycle();
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(
                placedOrder(OrderType.LIMIT, OrderDirection.BUY, 5, new BigDecimal("100.00"), "MOCK-L1")));
        stubCandle("105.00", "108.00", "99.00", "104.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().price()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("LIMIT 매수 — 저가가 지정가에 닿지 않으면 미체결이다")
    void limitBuyNotTouchedDoesNotFill() {
        stubTqqqCycle();
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(
                placedOrder(OrderType.LIMIT, OrderDirection.BUY, 5, new BigDecimal("100.00"), "MOCK-L2")));
        stubCandle("105.00", "108.00", "101.00", "104.00");

        assertThat(adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT)).isEmpty();
    }

    @Test
    @DisplayName("LIMIT 매도 — 장중 고가가 지정가를 터치하면(종가는 지정가 아래) 지정가에 체결된다")
    void limitSellTouchedIntradayFillsAtLimit() {
        stubTqqqCycle();
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(
                placedOrder(OrderType.LIMIT, OrderDirection.SELL, 5, new BigDecimal("100.00"), "MOCK-L3")));
        stubCandle("95.00", "101.00", "94.00", "96.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().price()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("LIMIT 매도 — 고가가 지정가에 닿지 않으면 미체결이다")
    void limitSellNotTouchedDoesNotFill() {
        stubTqqqCycle();
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(
                placedOrder(OrderType.LIMIT, OrderDirection.SELL, 5, new BigDecimal("100.00"), "MOCK-L4")));
        stubCandle("95.00", "99.00", "94.00", "96.00");

        assertThat(adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT)).isEmpty();
    }

    @Test
    @DisplayName("일봉이 없으면 현재가 단일값 봉으로 판정한다 — LIMIT 매수는 현재가<=지정가면 현재가에 체결")
    void noCandleFallsBackToCurrentPrice() {
        stubTqqqCycle();
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(
                placedOrder(OrderType.LIMIT, OrderDirection.BUY, 5, new BigDecimal("100.00"), "MOCK-L5")));
        when(priceFeed.fetchConfirmedCandle(eq(StrategyTicker.TQQQ), any())).thenReturn(Optional.empty());
        when(priceFeed.getPrice(StrategyTicker.TQQQ)).thenReturn(new BigDecimal("97.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().price()).isEqualByComparingTo("97.00");
    }

    @Test
    @DisplayName("경계값 — 종가==지정가면 등호 포함 조건이므로 LOC 매수도 체결된다")
    void locBuyFillsWhenClosingPriceEqualsLimit() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.LOC, OrderDirection.BUY, 5,
                new BigDecimal("100.00"), "MOCK-10");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        stubCandle("100.00", "100.00", "100.00", "100.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().price()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("PLACED 주문이 없으면 빈 리스트를 반환하고 시세를 조회하지 않는다")
    void returnsEmptyWhenNoPlacedOrders() {
        stubTqqqCycle();
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of());

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("getExecutions는 cycleId로 스코프된 주문만 조회한다 — 다른 사이클 주문은 대상이 아니다")
    void getExecutionsScopesByCurrentActiveCycle() {
        stubTqqqCycle();
        // 활성 사이클(CYCLE_ID)만 stub — findPlacedOrders가 다른 cycleId로 호출되면 stub 미스로 실패한다
        PlacedOrderView order = placedOrder(OrderType.MOC, OrderDirection.BUY, 3,
                new BigDecimal("100.00"), "MOCK-CYCLE-SCOPED");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        stubCandle("100.00", "100.00", "100.00", "100.00");

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, StrategyTicker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().externalOrderId()).isEqualTo("MOCK-CYCLE-SCOPED");
    }

    // --- place()/cancel() ---

    @Test
    @DisplayName("place()는 MOCK- 접두사 합성 externalOrderId를 담은 OrderResult를 반환한다")
    void placeAssignsSyntheticOrderId() {
        OrderInstruction instruction = new OrderInstruction(StrategyTicker.TQQQ, OrderDirection.BUY, OrderType.LOC,
                10, new BigDecimal("100.00"));

        OrderResult result = adapter().place(instruction, ACCOUNT);

        assertThat(result.externalOrderId()).startsWith("MOCK-");
    }

    @Test
    @DisplayName("cancel()은 예외 없이 아무 것도 하지 않는다")
    void cancelIsNoOp() {
        CancelInstruction instruction = new CancelInstruction(StrategyTicker.TQQQ, "MOCK-11");

        adapter().cancel(instruction, ACCOUNT);

        verifyNoInteractions(mockSimulationDataPort, priceFeed);
    }

    // --- getSellableQuantity() ---

    @Test
    @DisplayName("getSellableQuantity는 최신 포지션의 holdings를 반환한다")
    void getSellableQuantityReturnsLatestHoldings() {
        when(mockSimulationDataPort.findStrategiesByAccountId(ACCOUNT_ID)).thenReturn(List.of(TQQQ_STRATEGY));
        PositionView position = new PositionView(7, new BigDecimal("48.00"), new BigDecimal("500.00"));
        when(mockSimulationDataPort.findLatestPosition(STRATEGY_ID)).thenReturn(Optional.of(position));

        SellableQuantity sellable = adapter().getSellableQuantity(StrategyTicker.TQQQ, ACCOUNT);

        assertThat(sellable.symbol()).isEqualTo(StrategyTicker.TQQQ.name());
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
    @DisplayName("getPrice는 account와 무관하게 TossPriceApi 공통 시세로 위임한다")
    void getPriceDelegatesToPriceFeed() {
        when(priceFeed.getPrice(StrategyTicker.TQQQ)).thenReturn(new BigDecimal("123.45"));

        BigDecimal price = adapter().getPrice(StrategyTicker.TQQQ, ACCOUNT);

        assertThat(price).isEqualByComparingTo("123.45");
    }

    @Test
    @DisplayName("getPrevClose는 account와 무관하게 TossPriceApi 공통 시세로 위임한다")
    void getPrevCloseDelegatesToPriceFeed() {
        when(priceFeed.getPrevClose(StrategyTicker.TQQQ)).thenReturn(new BigDecimal("120.00"));

        BigDecimal prevClose = adapter().getPrevClose(StrategyTicker.TQQQ, ACCOUNT);

        assertThat(prevClose).isEqualByComparingTo("120.00");
    }

    // --- 스모크: getPresentBalance / getMargin ---

    @Test
    @DisplayName("getPresentBalance는 예외 없이 값을 채워 반환한다")
    void getPresentBalanceSmokeTest() {
        when(mockSimulationDataPort.findStrategiesByAccountId(ACCOUNT_ID)).thenReturn(List.of(TQQQ_STRATEGY));
        PositionView position = new PositionView(7, new BigDecimal("48.00"), new BigDecimal("500.00"));
        when(mockSimulationDataPort.findLatestPosition(STRATEGY_ID)).thenReturn(Optional.of(position));
        when(priceFeed.getPrice(StrategyTicker.TQQQ)).thenReturn(new BigDecimal("55.00"));

        PresentBalanceResult result = adapter().getPresentBalance(ACCOUNT);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getMargin은 예외 없이 값을 채워 반환한다")
    void getMarginSmokeTest() {
        List<MarginItem> margin = adapter().getMargin(ACCOUNT);

        assertThat(margin).isNotEmpty();
        assertThat(margin.getFirst().currency()).isEqualTo(Currency.USD);
    }
}
