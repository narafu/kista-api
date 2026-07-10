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
import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserSettingsPort;
import com.kista.domain.port.out.broker.ExecutionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    TradingReporter reporter;

    static final LocalDate TODAY = LocalDate.of(2026, 7, 9);
    static final BigDecimal CLOSE = new BigDecimal("22.00");

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", null,
            Account.Broker.KIS, null
    );
    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE
    );
    static final StrategyCycle CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), UUID.randomUUID(),
            new BigDecimal("1000.00"), null, TODAY, null, null, null
    );
    static final User USER = new User(
            ACCOUNT.userId(), "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
            null, null, null, null, NotificationChannel.TELEGRAM
    );
    static final BatchContext CTX = new BatchContext(STRATEGY, CYCLE, ACCOUNT, USER);
    static final AccountBalance BALANCE = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));

    @BeforeEach
    void setUp() {
        reporter = new TradingReporter(registry, orderPort, userNotificationPort,
                realtimeNotificationPort, userSettingsPort, cyclePositionPersistor);
        when(registry.require(ACCOUNT, ExecutionPort.class)).thenReturn(executionPort);
        lenient().when(userSettingsPort.findOrDefault(USER.id()))
                .thenReturn(UserSettings.defaultFor(USER.id())); // TRADING_ALERT 기본 활성
    }

    // PLACED 주문 픽스처 — id·externalOrderId 지정
    private static Order placedOrder(UUID id, String externalOrderId, int quantity) {
        return new Order(id, ACCOUNT.id(), CYCLE.id(), TODAY, Ticker.SOXL,
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
}
