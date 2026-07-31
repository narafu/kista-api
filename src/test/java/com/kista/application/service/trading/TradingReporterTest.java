package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.broker.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossApiException;
import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserSettingsPort;
import com.kista.domain.port.out.broker.BrokerOrderCorrectionPort;
import com.kista.domain.port.out.broker.ExecutionPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingReporterTest {

    @Mock BrokerAdapterRegistry registry;
    @Mock ExecutionPort executionPort;
    @Mock OrderPort orderPort;
    @Mock UserNotificationPort userNotificationPort;
    @Mock RealtimeNotificationPort realtimeNotificationPort;
    @Mock UserSettingsPort userSettingsPort;
    @Mock CyclePositionPersistor cyclePositionPersistor;
    @Mock BrokerOrderCorrectionPort brokerOrderPort;
    @Mock NotifyPort notifyPort;
    TradingReporter reporter;

    static final LocalDate TODAY = LocalDate.of(2026, 7, 9);
    static final BigDecimal CLOSE = new BigDecimal("22.00");

    static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE
    );
    static final StrategyCycle CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), UUID.randomUUID(),
            new BigDecimal("1000.00"), null, TODAY, null, null, null
    );
    static final User USER = DomainFixtures.activeUserWithTelegram(ACCOUNT.userId());
    static final BatchContext CTX = new BatchContext(STRATEGY, CYCLE, ACCOUNT, USER);
    static final AccountBalance BALANCE = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));

    // 마감 후 잔여 주문 취소는 Toss 전용(KIS는 정규장 종료 시 자동 취소) — 취소 검증 테스트만 별도 Toss 계좌 사용
    static final Account TOSS_ACCOUNT = DomainFixtures.tossAccount(UUID.randomUUID(), UUID.randomUUID());
    static final Strategy TOSS_STRATEGY = new Strategy(
            UUID.randomUUID(), TOSS_ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE
    );
    static final StrategyCycle TOSS_CYCLE = new StrategyCycle(
            UUID.randomUUID(), TOSS_STRATEGY.id(), UUID.randomUUID(),
            new BigDecimal("1000.00"), null, TODAY, null, null, null
    );
    static final User TOSS_USER = DomainFixtures.activeUserWithTelegram(TOSS_ACCOUNT.userId());
    static final BatchContext TOSS_CTX = new BatchContext(TOSS_STRATEGY, TOSS_CYCLE, TOSS_ACCOUNT, TOSS_USER);

    @BeforeEach
    void setUp() {
        reporter = new TradingReporter(registry, orderPort, userNotificationPort,
                realtimeNotificationPort, userSettingsPort, cyclePositionPersistor, notifyPort);
        lenient().when(registry.require(ACCOUNT, ExecutionPort.class)).thenReturn(executionPort);
        lenient().when(registry.require(TOSS_ACCOUNT, ExecutionPort.class)).thenReturn(executionPort);
        lenient().when(registry.require(TOSS_ACCOUNT, BrokerOrderCorrectionPort.class)).thenReturn(brokerOrderPort);
        lenient().when(userSettingsPort.findOrDefault(USER.id()))
                .thenReturn(UserSettings.defaultFor(USER.id())); // TRADING_ALERT 기본 활성
        lenient().when(userSettingsPort.findOrDefault(TOSS_USER.id()))
                .thenReturn(UserSettings.defaultFor(TOSS_USER.id()));
    }

    // PLACED 주문 픽스처 — id·externalOrderId 지정 (KIS 계좌/사이클 기준)
    private static Order placedOrder(UUID id, String externalOrderId, int quantity) {
        return new Order(id, ACCOUNT.id(), CYCLE.id(), TODAY, Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                quantity, new BigDecimal("20.00"), Order.OrderStatus.PLACED,
                externalOrderId, null, null);
    }

    // PLACED 주문 픽스처 — Toss 계좌/사이클 기준 (취소 로직 테스트 전용)
    private static Order tossPlacedOrder(UUID id, String externalOrderId, int quantity) {
        return new Order(id, TOSS_ACCOUNT.id(), TOSS_CYCLE.id(), TODAY, Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                quantity, new BigDecimal("20.00"), Order.OrderStatus.PLACED,
                externalOrderId, null, null);
    }

    private static Execution buyExecution(String externalOrderId, int quantity, String price) {
        BigDecimal p = new BigDecimal(price);
        return new Execution(TODAY, Ticker.SOXL, Order.OrderDirection.BUY,
                quantity, p, p.multiply(BigDecimal.valueOf(quantity)), externalOrderId);
    }

    @Test
    void 체결_내역이_없는_PLACED_주문은_CANCELLED_처리된다() {
        UUID unfilledId = UUID.randomUUID();
        UUID filledId = UUID.randomUUID();
        List<Order> orders = List.of(placedOrder(unfilledId, "E-UNFILLED", 5),
                placedOrder(filledId, "E-FILLED", 3));
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT))
                .thenReturn(List.of(buyExecution("E-FILLED", 3, "20.00")));

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, orders, null);

        verify(orderPort).markCancelled(unfilledId);
        verify(orderPort).markFilled(filledId, 3, new BigDecimal("20.00"), Order.OrderStatus.FILLED);
    }

    @Test
    void 부분_체결은_PARTIALLY_FILLED와_가중평균가를_기록한다() {
        UUID orderId = UUID.randomUUID();
        List<Order> orders = List.of(placedOrder(orderId, "E1", 10));
        // 3주 × $20.00 = $60.00 + 2주 × $21.00 = $42.00 → 5주, 가중평균 $102.00/5 = $20.40
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT))
                .thenReturn(List.of(buyExecution("E1", 3, "20.00"), buyExecution("E1", 2, "21.00")));

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, orders, null);

        verify(orderPort).markFilled(orderId, 5, new BigDecimal("20.40"), Order.OrderStatus.PARTIALLY_FILLED);
    }

    @Test
    void 체결이_전혀_없어도_미체결_PLACED_주문은_CANCELLED_처리된다() {
        // 버그 수정: executions가 비어도 PLACED 주문은 미체결로 간주해 CANCELLED 처리해야 함
        UUID orderId = UUID.randomUUID();
        List<Order> orders = List.of(placedOrder(orderId, "E1", 5));
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT)).thenReturn(List.of());

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, orders, null);

        verify(orderPort).markCancelled(orderId);
        verify(orderPort, never()).markFilled(any(), anyInt(), any(), any());
    }

    @Test
    void TRADING_ALERT_비활성이면_리포트를_발송하지_않는다() {
        UserSettings muted = mock(UserSettings.class);
        when(muted.isNotificationEnabled(NotificationType.TRADING_ALERT)).thenReturn(false);
        when(userSettingsPort.findOrDefault(USER.id())).thenReturn(muted);
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT)).thenReturn(List.of());

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, List.of(), null);

        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void 체결_건별로_SSE_실시간_알림을_발송한다() {
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT))
                .thenReturn(List.of(buyExecution("E1", 3, "20.00"), buyExecution("E2", 2, "21.00")));

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, List.of(), null);

        verify(realtimeNotificationPort, times(2)).notifyTrade(eq(USER.id()), any());
    }

    @Test
    void 마감_리포트는_체결_조회_전에_Toss_잔여_PLACED_주문을_취소한다() {
        UUID orderId = UUID.randomUUID();
        Order order = tossPlacedOrder(orderId, "E1", 5);
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, TOSS_ACCOUNT)).thenReturn(List.of());

        reporter.recordAndNotify(TODAY, TOSS_CTX, BALANCE, CLOSE, List.of(order), null);

        InOrder inOrder = inOrder(brokerOrderPort, executionPort);
        inOrder.verify(brokerOrderPort).cancel(order, TOSS_ACCOUNT);
        inOrder.verify(executionPort).getExecutions(TODAY, TODAY, Ticker.SOXL, TOSS_ACCOUNT);
    }

    @Test
    void Toss_취소_실패는_격리되고_관리자_알림으로_표면화되며_체결조회는_계속된다() {
        UUID orderId = UUID.randomUUID();
        Order order = tossPlacedOrder(orderId, "E1", 5);
        doThrow(new RuntimeException("이미 체결된 주문")).when(brokerOrderPort).cancel(order, TOSS_ACCOUNT);
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, TOSS_ACCOUNT))
                .thenReturn(List.of(buyExecution("E1", 5, "20.00")));

        reporter.recordAndNotify(TODAY, TOSS_CTX, BALANCE, CLOSE, List.of(order), null);

        verify(notifyPort).notifyError(any());
        verify(orderPort).markFilled(orderId, 5, new BigDecimal("20.00"), Order.OrderStatus.FILLED);
    }

    @Test
    void Toss_취소가_이미체결_409로_거부되면_관리자_알림없이_체결로_기록된다() {
        UUID orderId = UUID.randomUUID();
        Order order = tossPlacedOrder(orderId, "E1", 5);
        doThrow(new TossApiException(
                "Toss API 오류: 409 CONFLICT {\"error\":{\"code\":\"already-filled\",\"message\":\"체결 완료된 주문입니다.\"}}",
                null, true)).when(brokerOrderPort).cancel(order, TOSS_ACCOUNT);
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, TOSS_ACCOUNT))
                .thenReturn(List.of(buyExecution("E1", 5, "20.00")));

        reporter.recordAndNotify(TODAY, TOSS_CTX, BALANCE, CLOSE, List.of(order), null);

        verify(notifyPort, never()).notifyError(any());
        verify(orderPort).markFilled(orderId, 5, new BigDecimal("20.00"), Order.OrderStatus.FILLED);
    }

    @Test
    void KIS_계좌는_정규장_자동취소되므로_잔여_PLACED_주문_취소_호출을_생략한다() {
        UUID orderId = UUID.randomUUID();
        Order order = placedOrder(orderId, "E1", 5);
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT)).thenReturn(List.of());

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, List.of(order), null);

        verify(brokerOrderPort, never()).cancel(any(), any());
    }
}
