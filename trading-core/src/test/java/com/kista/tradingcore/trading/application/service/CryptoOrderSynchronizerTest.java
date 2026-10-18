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
import com.kista.tradingcore.matching.domain.model.DcaConfig;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CryptoOrderSynchronizerTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    static final BigDecimal PRICE = new BigDecimal("10000");

    CryptoExchangePort exchange = mock(CryptoExchangePort.class);
    OrderPort orderPort = mock(OrderPort.class);
    DcaExecutionRecorder recorder = mock(DcaExecutionRecorder.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    CryptoOrderSynchronizer sync = new CryptoOrderSynchronizer(orderPort, recorder, publisher);

    BatchContext ctx;
    Map<UUID, DcaConfig> configs;
    UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TradingAccount account = TradingFixtures.tradingAccount(UUID.randomUUID(), UUID.randomUUID());
        Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), StrategyType.DCA,
                StrategyStatus.ACTIVE, StrategyTicker.BTC, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), UUID.randomUUID(),
                BigDecimal.ZERO, null, LocalDate.of(2026, 10, 1), null, null, null);
        ctx = new BatchContext(strategy, cycle, account, TradingFixtures.tradingUserProfile(account.userId()));
        configs = Map.of(strategy.id(), new DcaConfig(OrderDirection.BUY, PRICE, OrderType.MARKET, DcaFrequency.DAILY,
                null, null, null, 13));
    }

    Order order(OrderStatus status, LocalDate tradeDate, String externalId) {
        return new Order(orderId, ctx.account().id(), ctx.currentCycle().id(), tradeDate, StrategyTicker.BTC,
                OrderType.MARKET, OrderTiming.AT_CLOSE, OrderDirection.BUY, "DCA", new BigDecimal("0.0001"), PRICE,
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
    void plannedFoundOnExchangeIsRecoveredWithoutResubmit() {
        Order o = order(OrderStatus.PLANNED, TODAY, null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.of(result(State.WAIT, "0", null)));
        run(o, 13);
        verify(orderPort).markPlaced(orderId, "upbit-uuid");
        verify(recorder).recordOrdered(ctx, TODAY, o, PRICE);
        verify(exchange, never()).buyMarketByAmount(any(), any(), any(), any());
        verify(exchange, never()).sellMarketByVolume(any(), any(), any(), any());
        verify(orderPort, never()).markFilled(any(), any(), any(), any());
    }

    @Test
    void plannedAlreadyFilledIsSettledInSameTick() {
        Order o = order(OrderStatus.PLANNED, TODAY, null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.of(result(State.DONE, "0.0001", "150000000")));
        run(o, 13);
        verify(orderPort).markPlaced(orderId, "upbit-uuid");
        verify(orderPort).markFilled(orderId, new BigDecimal("0.0001"), new BigDecimal("150000000"), OrderStatus.FILLED);
    }

    @Test
    void plannedOrderNotFoundBeforeHourEndsIsFailedThenRetried() {
        Order o = order(OrderStatus.PLANNED, TODAY, null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.empty());
        run(o, 13);
        verify(orderPort).markFailed(orderId);
        verify(recorder, never()).recordSkipped(any(), any(), any(), any(), any());
    }

    @Test
    void plannedOrderNotFoundAfterHourIsSkipped() {
        Order o = order(OrderStatus.PLANNED, TODAY, null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.empty());
        run(o, 14);
        verify(orderPort).markFailed(orderId);
        verify(recorder).recordSkipped(ctx, TODAY, DcaSkipReason.EXCHANGE_API_ERROR, "주문 접수 확인 실패", PRICE);
    }

    @Test
    void plannedOrderOfPastDayIsSkippedEvenInHour() {
        Order o = order(OrderStatus.PLANNED, TODAY.minusDays(1), null);
        when(exchange.findOrderByIdentifier(any(), anyString())).thenReturn(Optional.empty());
        run(o, 13);
        verify(recorder).recordSkipped(ctx, TODAY.minusDays(1), DcaSkipReason.EXCHANGE_API_ERROR, "주문 접수 확인 실패", PRICE);
    }

    @Test
    void transientLookupFailureLeavesPlanned() {
        Order o = order(OrderStatus.PLANNED, TODAY, null);
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
    void orderOfUnknownCycleIsUntouched() {
        Order o = new Order(orderId, ctx.account().id(), UUID.randomUUID(), TODAY, StrategyTicker.BTC, OrderType.MARKET,
                OrderTiming.AT_CLOSE, OrderDirection.BUY, "DCA", BigDecimal.ONE, PRICE, OrderStatus.PLANNED, null, null, null);
        run(o, 13);
        verifyNoMoreInteractions(exchange);
    }
}
