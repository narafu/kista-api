package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;
import com.kista.tradingcore.support.StubBrokerApiException;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.SubmittingOrder;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// 잔류 SUBMITTING 대사 — Toss만 선점 후 2~9분 창에서 같은 멱등키로 재요청 (spec 2026-10-16 §3.5)
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderSubmissionReconciler 단위 테스트")
class OrderSubmissionReconcilerTest {

    @Mock OrderPort orderPort;
    @Mock BrokerOrderCorrectionPort brokerPort;
    @Mock ApplicationEventPublisher eventPublisher;

    static final Instant NOW = Instant.parse("2026-10-16T19:30:00Z");
    static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 17);
    static final UUID CYCLE_ID = UUID.randomUUID();
    static final TradingAccount TOSS = account(Broker.TOSS);
    static final TradingAccount KIS = account(Broker.KIS);

    private static TradingAccount account(Broker broker) {
        return new TradingAccount(UUID.randomUUID(), UUID.randomUUID(), "계좌",
                new BrokerAccountRef(UUID.randomUUID(), "key", "secret", "123", "seq", broker));
    }

    private OrderSubmissionReconciler reconciler() {
        return new OrderSubmissionReconciler(orderPort, brokerPort, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Order submitting(UUID id) {
        return new Order(id, TOSS.id(), CYCLE_ID, TRADE_DATE, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, OrderDirection.BUY, 3, new BigDecimal("20.00"), OrderStatus.SUBMITTING, null, null, null);
    }

    // 선점 후 age만큼 지난 잔류 주문 1건을 조회 결과로 stub
    private Order stubLeftover(Duration age) {
        Order order = submitting(UUID.randomUUID());
        when(orderPort.findSubmittingByCycleAndDate(CYCLE_ID, TRADE_DATE))
                .thenReturn(List.of(new SubmittingOrder(order, NOW.minus(age))));
        return order;
    }

    private void expectUnresolvedAlert(Order order) {
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.message().contains("[접수 결과 불명]") && tee.message().contains(order.id().toString())));
    }

    @Test
    @DisplayName("Toss 선점 3분 경과 — 같은 멱등키로 재요청해 원 주문번호로 PLACED 확정")
    void tossWithinWindow_resendsWithSameKey_andMarksPlaced() {
        Order order = stubLeftover(Duration.ofMinutes(3));
        when(orderPort.findById(order.id())).thenReturn(Optional.of(order));
        when(brokerPort.place(any(), eq(TOSS.brokerRef()))).thenReturn(new OrderResult("EXT"));

        reconciler().reconcile(CYCLE_ID, TRADE_DATE, TOSS);

        ArgumentCaptor<OrderInstruction> captor = ArgumentCaptor.forClass(OrderInstruction.class);
        verify(brokerPort).place(captor.capture(), eq(TOSS.brokerRef()));
        assertThat(captor.getValue().clientOrderId()).isEqualTo(order.id().toString());
        verify(orderPort).markPlaced(order.id(), "EXT");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("선점 2분 미만 — 다른 스레드가 접수 중일 수 있어 건드리지 않는다")
    void tossYoungerThan2Min_untouched() {
        stubLeftover(Duration.ofSeconds(119));

        reconciler().reconcile(CYCLE_ID, TRADE_DATE, TOSS);

        verify(brokerPort, never()).place(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Toss 선점 9분 이상 — 멱등키 만료 위험이라 재요청 없이 알림")
    void toss9MinOrOlder_alertsWithoutResend() {
        Order order = stubLeftover(Duration.ofMinutes(9));

        reconciler().reconcile(CYCLE_ID, TRADE_DATE, TOSS);

        verify(brokerPort, never()).place(any(), any());
        expectUnresolvedAlert(order);
    }

    @Test
    @DisplayName("KIS — 멱등키가 없어 재요청 없이 알림")
    void kis_alertsWithoutResend() {
        Order order = stubLeftover(Duration.ofMinutes(5));

        reconciler().reconcile(CYCLE_ID, TRADE_DATE, KIS);

        verify(brokerPort, never()).place(any(), any());
        expectUnresolvedAlert(order);
    }

    @Test
    @DisplayName("재확인 시 더 이상 SUBMITTING이 아니면 재요청하지 않는다")
    void toss_rowNoLongerSubmitting_skips() {
        Order order = stubLeftover(Duration.ofMinutes(3));
        when(orderPort.findById(order.id())).thenReturn(Optional.of(order.withPlaced("EXT")));

        reconciler().reconcile(CYCLE_ID, TRADE_DATE, TOSS);

        verify(brokerPort, never()).place(any(), any());
    }

    @Test
    @DisplayName("재요청이 확정 거절이면 FAILED")
    void toss_resendRejected_marksFailed() {
        Order order = stubLeftover(Duration.ofMinutes(3));
        when(orderPort.findById(order.id())).thenReturn(Optional.of(order));
        when(brokerPort.place(any(), any())).thenThrow(StubBrokerApiException.rejected("주문가능금액 초과"));

        reconciler().reconcile(CYCLE_ID, TRADE_DATE, TOSS);

        verify(orderPort).markFailed(order.id());
    }

    @Test
    @DisplayName("재요청도 결과 불명(409 처리 중 등)이면 SUBMITTING 유지 + 알림")
    void toss_resendAmbiguous_keepsAndAlerts() {
        Order order = stubLeftover(Duration.ofMinutes(3));
        when(orderPort.findById(order.id())).thenReturn(Optional.of(order));
        when(brokerPort.place(any(), any())).thenThrow(new StubBrokerApiException("request-in-progress"));

        reconciler().reconcile(CYCLE_ID, TRADE_DATE, TOSS);

        verify(orderPort, never()).markFailed(any());
        verify(orderPort, never()).markPlaced(any(), any());
        expectUnresolvedAlert(order);
    }

    @Test
    @DisplayName("잔류 주문이 없으면 아무것도 하지 않는다")
    void noLeftover_noop() {
        when(orderPort.findSubmittingByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of());

        reconciler().reconcile(CYCLE_ID, TRADE_DATE, TOSS);

        verifyNoInteractions(brokerPort, eventPublisher);
    }
}
