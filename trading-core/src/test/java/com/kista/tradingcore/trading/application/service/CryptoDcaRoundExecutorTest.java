package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.support.StubBrokerApiException;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.port.output.CryptoFearGreedIndexPort;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CryptoDcaRoundExecutorTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    static final LocalDateTime NOW = TODAY.atTime(13, 10);
    static final BigDecimal BTC_PRICE = new BigDecimal("30000000");

    CryptoExchangePort exchange = mock(CryptoExchangePort.class);
    OrderPort orderPort = mock(OrderPort.class);
    DcaExecutionPort executionPort = mock(DcaExecutionPort.class);
    DcaExecutionRecorder recorder = mock(DcaExecutionRecorder.class);
    CryptoFearGreedIndexPort fearGreed = mock(CryptoFearGreedIndexPort.class);
    CryptoDcaRoundExecutor executor = new CryptoDcaRoundExecutor(fearGreed, orderPort, executionPort, recorder);

    TradingAccount account;

    @BeforeEach
    void setUp() {
        account = TradingFixtures.tradingAccount(UUID.randomUUID(), UUID.randomUUID());
        when(exchange.getCompletedDailyCandles(any(), anyInt())).thenAnswer(i -> candles(TODAY.minusDays(1)));
        when(exchange.getCurrentPrices(any())).thenReturn(Map.of(StrategyTicker.BTC, BTC_PRICE, StrategyTicker.ETH, new BigDecimal("2000000")));
        when(orderPort.findByStrategyId(any(), any(), any())).thenReturn(List.of());
        when(executionPort.find(any(), any())).thenReturn(Optional.empty());
        when(orderPort.save(any())).thenAnswer(i -> {
            Order o = i.getArgument(0);
            return new Order(UUID.randomUUID(), o.accountId(), o.strategyCycleId(), o.tradeDate(), o.ticker(), o.orderType(),
                    o.timing(), o.direction(), o.orderLeg(), o.quantity(), o.price(), o.status(), null, null, null);
        });
        when(exchange.buyMarketByAmount(any(), any(), any(), anyString())).thenAnswer(i -> placed(i.getArgument(3)));
        when(exchange.sellMarketByVolume(any(), any(), any(), anyString())).thenAnswer(i -> placed(i.getArgument(3)));
    }

    static List<CryptoCandle> candles(LocalDate last) {
        return IntStream.range(0, 250).mapToObj(i -> new CryptoCandle(last.minusDays(249 - i), new BigDecimal("100"))).toList();
    }

    static CryptoOrderResult placed(String identifier) {
        return new CryptoOrderResult("uuid-1", identifier, CryptoOrderResult.State.WAIT, BigDecimal.ZERO, null, BigDecimal.ZERO);
    }

    void balance(String krw, String btcAvailable) {
        when(exchange.getBalance(any())).thenReturn(new CryptoBalance(new BigDecimal(krw), BigDecimal.ZERO,
                Map.of(StrategyTicker.BTC, new CryptoBalance.Holding(new BigDecimal(btcAvailable), BigDecimal.ZERO, new BigDecimal("1")))));
    }

    BatchContext ctx(StrategyTicker ticker) {
        Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), StrategyType.DCA, StrategyStatus.ACTIVE, ticker, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), UUID.randomUUID(),
                BigDecimal.ZERO, null, LocalDate.of(2026, 10, 1), null, null, null);
        return new BatchContext(strategy, cycle, account, TradingFixtures.tradingUserProfile(account.userId()));
    }

    DcaConfig config(OrderDirection direction, String amount, int hour) {
        return new DcaConfig(direction, new BigDecimal(amount), OrderType.MARKET, DcaFrequency.DAILY, null, null, List.of(), hour);
    }

    void run(LocalDateTime now, Map<BatchContext, DcaConfig> entries) {
        List<BatchContext> ctxs = new ArrayList<>(entries.keySet());
        Map<UUID, DcaConfig> configs = new java.util.HashMap<>();
        entries.forEach((c, cfg) -> configs.put(c.strategy().id(), cfg));
        executor.executeAccount(exchange, ctxs, configs, now);
    }

    void run(LocalDateTime now, BatchContext c, DcaConfig cfg) {
        run(now, Map.of(c, cfg));
    }

    @Test
    void buyCapsToAvailableKrwMinusFee() {
        balance("50000", "0");
        BatchContext c = ctx(StrategyTicker.BTC);
        run(NOW, c, config(OrderDirection.BUY, "100000.00", 13));
        var amount = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        verify(exchange).buyMarketByAmount(any(), eq(StrategyTicker.BTC), amount.capture(), anyString());
        assertThat(amount.getValue()).isEqualTo(new BigDecimal("49975")); // isEqualTo는 scale까지 비교
        assertThat(amount.getValue().scale()).isZero();
    }

    @Test
    void buyBelowMinAfterCapIsSkipped() {
        balance("5002", "0");
        BatchContext c = ctx(StrategyTicker.BTC);
        run(NOW, c, config(OrderDirection.BUY, "100000", 13));
        verify(recorder).recordSkipped(eq(c), eq(TODAY), eq(DcaSkipReason.BELOW_MIN_ORDER_AMOUNT), eq("주문 금액 4999 < 최소 5000"), eq(BTC_PRICE));
        verify(orderPort, never()).save(any());
    }

    @Test
    void secondStrategyUsesLocallyDeductedBalance() {
        balance("40000", "0");
        BatchContext btc = ctx(StrategyTicker.BTC);
        BatchContext eth = ctx(StrategyTicker.ETH);
        // 순서 보장을 위해 LinkedHashMap 대신 두 번째 인자로 순차 전달
        Map<BatchContext, DcaConfig> m = new java.util.LinkedHashMap<>();
        m.put(btc, config(OrderDirection.BUY, "30000", 13));
        m.put(eth, config(OrderDirection.BUY, "30000", 13));
        run(NOW, m);
        verify(exchange).buyMarketByAmount(any(), eq(StrategyTicker.BTC), eq(new BigDecimal("30000")), anyString());
        verify(exchange).buyMarketByAmount(any(), eq(StrategyTicker.ETH), eq(new BigDecimal("9980")), anyString());
    }

    @Test
    void sellVolumeFloorsToEightDecimalsAndCapsHoldings() {
        balance("0", "1");
        BatchContext c = ctx(StrategyTicker.BTC);
        run(NOW, c, config(OrderDirection.SELL, "10000", 13));
        verify(exchange).sellMarketByVolume(any(), eq(StrategyTicker.BTC), eq(new BigDecimal("0.00033333")), anyString());
    }

    @Test
    void sellCapsToHoldings() {
        balance("0", "0.0002");
        BatchContext c = ctx(StrategyTicker.BTC);
        run(NOW, c, config(OrderDirection.SELL, "10000", 13));
        verify(exchange).sellMarketByVolume(any(), eq(StrategyTicker.BTC), eq(new BigDecimal("0.0002")), anyString());
    }

    @Test
    void sellWithoutHoldingsIsNoHoldings() {
        balance("0", "0");
        BatchContext c = ctx(StrategyTicker.BTC);
        run(NOW, c, config(OrderDirection.SELL, "10000", 13));
        verify(recorder).recordSkipped(c, TODAY, DcaSkipReason.NO_HOLDINGS, "보유 수량 0", BTC_PRICE);
        verify(orderPort, never()).save(any());
    }

    @Test
    void freshnessBeforeNineExpectsTwoDaysAgo() {
        balance("100000", "0");
        when(exchange.getCompletedDailyCandles(any(), anyInt())).thenAnswer(i -> candles(TODAY.minusDays(2)));
        BatchContext c = ctx(StrategyTicker.BTC);
        run(TODAY.atTime(3, 10), c, config(OrderDirection.BUY, "10000", 3));
        verify(exchange).buyMarketByAmount(any(), any(), any(), anyString());

        BatchContext c2 = ctx(StrategyTicker.BTC);
        run(TODAY.atTime(10, 10), c2, config(OrderDirection.BUY, "10000", 10));
        verify(recorder).recordSkipped(eq(c2), eq(TODAY), eq(DcaSkipReason.DATA_UNAVAILABLE), anyString(), any());
    }

    @Test
    void notExecutionHourDoesNothing() {
        run(TODAY.atTime(12, 50), ctx(StrategyTicker.BTC), config(OrderDirection.BUY, "10000", 13));
        verifyNoInteractions(exchange, recorder);
    }

    @Test
    void alreadyExecutedTodayDoesNothing() {
        when(executionPort.find(any(), eq(TODAY))).thenReturn(Optional.of(mock(DcaExecution.class)));
        run(NOW, ctx(StrategyTicker.BTC), config(OrderDirection.BUY, "10000", 13));
        verifyNoInteractions(exchange, recorder);
    }

    Order existing(BatchContext c, OrderStatus status) {
        return new Order(UUID.randomUUID(), account.id(), c.currentCycle().id(), TODAY, StrategyTicker.BTC, OrderType.MARKET,
                OrderTiming.AT_CLOSE, OrderDirection.BUY, "DCA_BUY", BigDecimal.ONE, BTC_PRICE, status, null, null, null);
    }

    @Test
    void confirmedOrderIsRecoveredNotReordered() {
        for (OrderStatus status : List.of(OrderStatus.PLACED, OrderStatus.FILLED)) {
            BatchContext c = ctx(StrategyTicker.BTC);
            Order existing = existing(c, status);
            when(orderPort.findByStrategyId(c.strategy().id(), TODAY, TODAY)).thenReturn(List.of(existing));
            run(NOW, c, config(OrderDirection.BUY, "10000", 13));
            verify(recorder).recordOrdered(c, TODAY, existing, BTC_PRICE);
        }
        verifyNoInteractions(exchange);
    }

    @Test
    void submittingOrderBlocksWithoutRecording() {
        for (OrderStatus status : List.of(OrderStatus.SUBMITTING, OrderStatus.PLANNED)) {
            BatchContext c = ctx(StrategyTicker.BTC);
            when(orderPort.findByStrategyId(c.strategy().id(), TODAY, TODAY)).thenReturn(List.of(existing(c, status)));
            run(NOW, c, config(OrderDirection.BUY, "10000", 13));
        }
        verifyNoInteractions(exchange, recorder);
        verify(orderPort, never()).save(any());
    }

    @Test
    void failedOrderDoesNotBlockRetry() {
        balance("100000", "0");
        BatchContext c = ctx(StrategyTicker.BTC);
        when(orderPort.findByStrategyId(c.strategy().id(), TODAY, TODAY)).thenReturn(List.of(existing(c, OrderStatus.FAILED)));
        run(NOW, c, config(OrderDirection.BUY, "10000", 13));
        verify(exchange).buyMarketByAmount(any(), any(), any(), anyString());
    }

    @Test
    void keyExpiredOnPlacementSkipsRemainingWithoutCalls() {
        balance("100000", "0");
        when(exchange.buyMarketByAmount(any(), any(), any(), anyString())).thenThrow(new BrokerCredentialException());
        BatchContext btc = ctx(StrategyTicker.BTC);
        BatchContext eth = ctx(StrategyTicker.ETH);
        Map<BatchContext, DcaConfig> m = new java.util.LinkedHashMap<>();
        m.put(btc, config(OrderDirection.BUY, "10000", 13));
        m.put(eth, config(OrderDirection.BUY, "10000", 13));
        run(NOW, m);
        verify(recorder).recordSkipped(eq(btc), eq(TODAY), eq(DcaSkipReason.API_KEY_EXPIRED), anyString(), eq(BTC_PRICE));
        verify(recorder).recordSkipped(eq(eth), eq(TODAY), eq(DcaSkipReason.API_KEY_EXPIRED), anyString(), isNull());
        verify(exchange, org.mockito.Mockito.times(1)).buyMarketByAmount(any(), any(), any(), anyString());
    }

    @Test
    void transientPlacementStillDeductsForNextStrategy() {
        balance("40000", "0");
        when(exchange.buyMarketByAmount(any(), eq(StrategyTicker.BTC), any(), anyString())).thenThrow(new StubBrokerApiException("timeout"));
        Map<BatchContext, DcaConfig> m = new java.util.LinkedHashMap<>();
        m.put(ctx(StrategyTicker.BTC), config(OrderDirection.BUY, "30000", 13));
        m.put(ctx(StrategyTicker.ETH), config(OrderDirection.BUY, "30000", 13));
        run(NOW, m);
        verify(exchange).buyMarketByAmount(any(), eq(StrategyTicker.ETH), eq(new BigDecimal("9980")), anyString());
    }

    @Test
    void sellDeductsHoldingsForNextSellOfSameTicker() {
        balance("0", "0.0006");
        Map<BatchContext, DcaConfig> m = new java.util.LinkedHashMap<>();
        m.put(ctx(StrategyTicker.BTC), config(OrderDirection.SELL, "10000", 13));
        m.put(ctx(StrategyTicker.BTC), config(OrderDirection.SELL, "10000", 13));
        run(NOW, m);
        verify(exchange).sellMarketByVolume(any(), eq(StrategyTicker.BTC), eq(new BigDecimal("0.00033333")), anyString());
        verify(exchange).sellMarketByVolume(any(), eq(StrategyTicker.BTC), eq(new BigDecimal("0.00026667")), anyString());
    }

    @Test
    void keyExpiredSkipsAllTargets() {
        when(exchange.getBalance(any())).thenThrow(new BrokerCredentialException());
        BatchContext btc = ctx(StrategyTicker.BTC);
        BatchContext eth = ctx(StrategyTicker.ETH);
        run(NOW, Map.of(btc, config(OrderDirection.BUY, "10000", 13), eth, config(OrderDirection.BUY, "10000", 13)));
        verify(recorder).recordSkipped(eq(btc), eq(TODAY), eq(DcaSkipReason.API_KEY_EXPIRED), anyString(), isNull());
        verify(recorder).recordSkipped(eq(eth), eq(TODAY), eq(DcaSkipReason.API_KEY_EXPIRED), anyString(), isNull());
    }

    @Test
    void transientBeforeMinute50Retries() {
        when(exchange.getBalance(any())).thenThrow(new RuntimeException("timeout"));
        run(NOW, ctx(StrategyTicker.BTC), config(OrderDirection.BUY, "10000", 13));
        verifyNoInteractions(recorder);
    }

    @Test
    void transientAtMinute50IsSkipped() {
        when(exchange.getBalance(any())).thenThrow(new RuntimeException("timeout"));
        BatchContext c = ctx(StrategyTicker.BTC);
        run(TODAY.atTime(13, 50), c, config(OrderDirection.BUY, "10000", 13));
        verify(recorder).recordSkipped(eq(c), eq(TODAY), eq(DcaSkipReason.EXCHANGE_API_ERROR), anyString(), isNull());
    }

    @Test
    void candleTransientFailureRetriesInsteadOfDataUnavailable() {
        balance("100000", "0");
        when(exchange.getCompletedDailyCandles(any(), anyInt())).thenThrow(new RuntimeException("timeout"));
        run(NOW, ctx(StrategyTicker.BTC), config(OrderDirection.BUY, "10000", 13));
        verifyNoInteractions(recorder);
    }

    @Test
    void unknownPlacementLeavesSubmitting() {
        balance("100000", "0");
        when(exchange.buyMarketByAmount(any(), any(), any(), anyString())).thenThrow(new StubBrokerApiException("timeout"));
        BatchContext c = ctx(StrategyTicker.BTC);
        run(NOW, c, config(OrderDirection.BUY, "10000", 13));
        verify(orderPort).markSubmitting(any());
        verify(orderPort, never()).markPlaced(any(), any());
        verify(orderPort, never()).markFailed(any());
        verify(recorder, never()).recordSkipped(any(), any(), any(), any(), any());
    }

    @Test
    void rejectedPlacementIsFailedAndSkipped() {
        balance("100000", "0");
        when(exchange.buyMarketByAmount(any(), any(), any(), anyString())).thenThrow(StubBrokerApiException.rejected("insufficient"));
        BatchContext c = ctx(StrategyTicker.BTC);
        run(NOW, c, config(OrderDirection.BUY, "10000", 13));
        verify(recorder).recordSkipped(eq(c), eq(TODAY), eq(DcaSkipReason.EXCHANGE_API_ERROR), anyString(), eq(BTC_PRICE));
        verify(orderPort).markFailed(any());
        verify(orderPort, never()).markPlaced(any(), any());
    }

    @Test
    void successRecordsOrderedThenMarksPlaced() {
        balance("100000", "0");
        BatchContext c = ctx(StrategyTicker.BTC);
        run(NOW, c, config(OrderDirection.BUY, "10000", 13));
        var inOrder = org.mockito.Mockito.inOrder(orderPort, recorder);
        inOrder.verify(orderPort).markSubmitting(any());
        inOrder.verify(recorder).recordOrdered(eq(c), eq(TODAY), any(Order.class), eq(BTC_PRICE));
        inOrder.verify(orderPort).markPlaced(any(), eq("uuid-1"));
    }

    @Test
    void monthEndCorrectionRunsOnLastDay() {
        balance("100000", "0");
        LocalDate feb28 = LocalDate.of(2026, 2, 28);
        when(exchange.getCompletedDailyCandles(any(), anyInt())).thenAnswer(i -> candles(feb28.minusDays(1)));
        DcaConfig monthly = new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET, DcaFrequency.MONTHLY,
                null, 31, List.of(), 13);
        run(feb28.atTime(13, 10), ctx(StrategyTicker.BTC), monthly);
        verify(exchange).buyMarketByAmount(any(), any(), any(), anyString());
    }
}
