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
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult.State;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.event.CryptoOrderFilledEvent;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CryptoOrderSynchronizerTest {

    static final String KEY = "11111111-1111-1111-1111-111111111111";

    static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    static final BigDecimal PRICE = new BigDecimal("10000");

    CryptoExchangePort exchange = mock(CryptoExchangePort.class);
    OrderPort orderPort = mock(OrderPort.class);
    DcaExecutionRecorder recorder = mock(DcaExecutionRecorder.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    CryptoOrderSynchronizer sync = new CryptoOrderSynchronizer(orderPort, recorder, publisher);

    BatchContext ctx;
    Map<UUID, DcaStrategyConfig> configs;
    UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TradingAccount account = TradingFixtures.tradingAccount(UUID.randomUUID(), UUID.randomUUID());
        Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), StrategyType.DCA,
                StrategyStatus.ACTIVE, StrategyTicker.BTC, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), UUID.randomUUID(),
                BigDecimal.ZERO, null, LocalDate.of(2026, 10, 1), null, null, null);
        ctx = new BatchContext(strategy, cycle, account, TradingFixtures.tradingUserProfile(account.userId()));
        configs = Map.of(strategy.id(), com.kista.tradingcore.support.TradingFixtures.dcaConfig(
                com.kista.tradingcore.support.TradingFixtures.cryptoDcaRule(KEY, OrderDirection.BUY, "10000", 13)));
    }

    Order order(OrderStatus status, LocalDate tradeDate, String externalId) {
        return new Order(orderId, ctx.account().id(), ctx.currentCycle().id(), tradeDate, StrategyTicker.BTC,
                OrderType.MARKET, OrderTiming.AT_CLOSE, OrderDirection.BUY, "DCA_" + KEY, new BigDecimal("0.0001"), PRICE,
                status, externalId, null, null);
    }

    CryptoOrderResult result(State state, String volume, String avg) {
        return new CryptoOrderResult("upbit-uuid", orderId.toString(), state, new BigDecimal(volume),
                avg == null ? null : new BigDecimal(avg), BigDecimal.ZERO);
    }

    void run(Order o, int hour) {
        when(orderPort.findOpenByAccountId(ctx.account().id())).thenReturn(List.of(o));
        sync.sync(exchange, List.of(ctx), configs, TODAY.atTime(hour, 20));
    }

    @Test
    void submittingFoundOnExchangeIsRecoveredWithoutResubmit() {
        Order o = order(OrderStatus.SUBMITTING, TODAY, null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.of(result(State.WAIT, "0", null)));
        run(o, 13);
        InOrder io = inOrder(recorder, orderPort);
        io.verify(recorder).recordOrdered(ctx, TODAY, KEY, o, PRICE);
        io.verify(orderPort).markPlaced(orderId, "upbit-uuid");
        verify(exchange, never()).buyMarketByAmount(any(), any(), any(), any());
        verify(exchange, never()).sellMarketByVolume(any(), any(), any(), any());
        verify(orderPort, never()).markFilled(any(), any(), any(), any());
    }

    @Test
    void submittingAlreadyFilledIsSettledInSameTick() {
        Order o = order(OrderStatus.SUBMITTING, TODAY, null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.of(result(State.DONE, "0.0001", "150000000")));
        run(o, 13);
        verify(orderPort).markPlaced(orderId, "upbit-uuid");
        verify(orderPort).markFilled(orderId, new BigDecimal("0.0001"), new BigDecimal("150000000"), OrderStatus.FILLED);
    }

    @Test
    void submittingOrderNotFoundBeforeHourEndsIsFailedThenRetried() {
        Order o = order(OrderStatus.SUBMITTING, TODAY, null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.empty());
        run(o, 13);
        verify(orderPort).markFailed(orderId);
        verify(recorder, never()).recordSkipped(any(), any(), any(), any(), any(), any());
    }

    @Test
    void submittingOrderNotFoundAfterHourIsSkipped() {
        Order o = order(OrderStatus.SUBMITTING, TODAY, null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.empty());
        run(o, 14);
        InOrder io = inOrder(recorder, orderPort);
        io.verify(recorder).recordSkipped(ctx, TODAY, KEY, DcaSkipReason.EXCHANGE_API_ERROR, "주문 접수 확인 실패", PRICE);
        io.verify(orderPort).markFailed(orderId);
    }

    @Test
    void submittingOrderOfPastDayIsSkippedEvenInHour() {
        Order o = order(OrderStatus.SUBMITTING, TODAY.minusDays(1), null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.empty());
        run(o, 13);
        verify(recorder).recordSkipped(ctx, TODAY.minusDays(1), KEY, DcaSkipReason.EXCHANGE_API_ERROR, "주문 접수 확인 실패", PRICE);
    }

    @Test
    void recordSkippedFailureLeavesOrderSubmitting() {
        Order o = order(OrderStatus.SUBMITTING, TODAY, null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("db")).when(recorder).recordSkipped(any(), any(), any(), any(), any(), any());
        run(o, 14);
        verify(orderPort, never()).markFailed(any());
    }

    @Test
    void recordOrderedFailureLeavesOrderSubmitting() {
        Order o = order(OrderStatus.SUBMITTING, TODAY, null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.of(result(State.WAIT, "0", null)));
        doThrow(new RuntimeException("db")).when(recorder).recordOrdered(any(), any(), any(), any(), any());
        run(o, 13);
        verify(orderPort, never()).markPlaced(any(), any());
    }

    @Test
    void filledWithoutAvgPriceIsSkipped() {
        Order o = order(OrderStatus.PLACED, TODAY, "upbit-uuid");
        when(exchange.findOrderByUuid(any(), anyString())).thenReturn(Optional.of(result(State.DONE, "0.0001", null)));
        run(o, 13);
        verify(orderPort, never()).markFilled(any(), any(), any(), any());
    }

    @Test
    void transientLookupFailureLeavesSubmitting() {
        Order o = order(OrderStatus.SUBMITTING, TODAY, null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenThrow(new RuntimeException("timeout"));
        run(o, 13);
        verifyNoMoreInteractions(recorder);
        verify(orderPort, never()).markFailed(any());
        verify(orderPort, never()).markPlaced(any(), any());
    }

    @Test
    void placedFilledIsMarkedAndEventPublished() {
        Order o = order(OrderStatus.PLACED, TODAY, "upbit-uuid");
        when(exchange.findOrderByUuid(any(), anyString())).thenReturn(Optional.of(result(State.CANCEL, "0.0001", "150000000")));
        run(o, 13);
        verify(orderPort).markFilled(orderId, new BigDecimal("0.0001"), new BigDecimal("150000000"), OrderStatus.FILLED);
        ArgumentCaptor<CryptoOrderFilledEvent> cap = ArgumentCaptor.forClass(CryptoOrderFilledEvent.class);
        verify(publisher).publishEvent(cap.capture());
        assertThat(cap.getValue().amountKrw()).isEqualByComparingTo("15000");
        assertThat(cap.getValue().strategyId()).isEqualTo(ctx.strategy().id());
    }

    @Test
    void placedCancelledWithoutFillIsCancelled() {
        Order o = order(OrderStatus.PLACED, TODAY, "upbit-uuid");
        when(exchange.findOrderByUuid(any(), anyString())).thenReturn(Optional.of(result(State.CANCEL, "0", null)));
        run(o, 13);
        verify(orderPort).markCancelled(orderId);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void placedStillWaitingIsLeftAlone() {
        Order o = order(OrderStatus.PLACED, TODAY, "upbit-uuid");
        when(exchange.findOrderByUuid(any(), anyString())).thenReturn(Optional.of(result(State.WAIT, "0", null)));
        run(o, 13);
        verify(orderPort, never()).markCancelled(any());
        verify(orderPort, never()).markFilled(any(), any(), any(), any());
    }

    @Test
    void plannedLeftoverIsCancelledWithoutLookup() {
        Order o = order(OrderStatus.PLANNED, TODAY, null);
        run(o, 14);
        InOrder io = inOrder(recorder, orderPort);
        io.verify(recorder).recordSkipped(ctx, TODAY, KEY, DcaSkipReason.EXCHANGE_API_ERROR, "주문 접수 확인 실패", PRICE);
        io.verify(orderPort).cancelPlanned(orderId);
        verifyNoMoreInteractions(exchange);
    }

    @Test
    void plannedLeftoverInHourIsCancelledWithoutRecord() {
        Order o = order(OrderStatus.PLANNED, TODAY, null);
        run(o, 13);
        verify(orderPort).cancelPlanned(orderId);
        verify(recorder, never()).recordSkipped(any(), any(), any(), any(), any(), any());
    }

    @Test
    void orderOfUnknownCycleIsUntouched() {
        Order o = new Order(orderId, ctx.account().id(), UUID.randomUUID(), TODAY, StrategyTicker.BTC, OrderType.MARKET,
                OrderTiming.AT_CLOSE, OrderDirection.BUY, "DCA_" + KEY, BigDecimal.ONE, PRICE, OrderStatus.SUBMITTING, null, null, null);
        run(o, 13);
        verifyNoMoreInteractions(exchange);
    }
}
