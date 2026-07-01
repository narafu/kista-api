package com.kista.application.service.admin;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminOrderCorrectionCommand;
import com.kista.domain.model.admin.AdminOrderCorrectionResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserPort;
import com.kista.domain.port.out.broker.BrokerOrderCorrectionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOrderCorrectionServiceTest {

    @Mock UserPort userPort;
    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock OrderPort orderPort;
    @Mock AuditLogPort auditLogPort;
    @Mock BrokerAdapterRegistry brokerAdapterRegistry;
    @Mock BrokerOrderCorrectionPort brokerOrderCorrectionPort;

    @InjectMocks AdminOrderCorrectionService service;

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID STRATEGY_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID CYCLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000050");

    @Test
    void correctOrder_plannedEdit_updatesPriceAndQuantity() {
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user());
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy());
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(cycle()));
        when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(plannedOrder()));

        AdminOrderCorrectionResult result = service.correctOrder(ADMIN_ID, plannedEditCommand());

        verify(orderPort).updatePlannedOrder(ORDER_ID, new BigDecimal("250.00"), 3);
        assertThat(result.mode()).isEqualTo(AdminOrderCorrectionCommand.Mode.PLANNED_EDIT);
        assertThat(result.resultingStatus()).isEqualTo(Order.OrderStatus.PLANNED);
    }

    @Test
    void correctOrder_filledCorrection_addsCompensatingFillAndUpdatesCyclePosition() {
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user());
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy());
        when(strategyPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(cycle()));
        when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(filledOrder()));
        when(cyclePositionPort.findLatestByCycleId(CYCLE_ID, 1)).thenReturn(List.of(latestPosition()));

        AdminOrderCorrectionResult result = service.correctOrder(ADMIN_ID, filledCorrectionCommand());

        verify(orderPort).saveAll(any());
        verify(cyclePositionPort).save(argThat(position -> position.holdings() == 0));
        verify(strategyCyclePort).markEnded(CYCLE_ID, new BigDecimal("7200.05"), LocalDate.of(2026, 7, 1));
        assertThat(result.resultingStatus()).isEqualTo(Order.OrderStatus.FILLED);
        assertThat(result.cycleEnded()).isTrue();
    }

    @Test
    void correctOrder_placedReplace_cancelsAndPlacesReplacementOrder() {
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user());
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy());
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(cycle()));
        when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(placedOrder()));
        when(brokerAdapterRegistry.require(account(), BrokerOrderCorrectionPort.class)).thenReturn(brokerOrderCorrectionPort);
        when(brokerOrderCorrectionPort.place(any(), any())).thenAnswer(invocation -> ((Order) invocation.getArgument(0)).withPlaced("REPLACED-1"));

        AdminOrderCorrectionResult result = service.correctOrder(ADMIN_ID, placedReplaceCommand());

        verify(brokerOrderCorrectionPort).cancel(placedOrder(), account());
        verify(orderPort).markCancelled(ORDER_ID);
        verify(orderPort).saveAll(argThat(orders -> orders.size() == 1
                && "REPLACED-1".equals(orders.getFirst().externalOrderId())
                && orders.getFirst().quantity() == 2
                && orders.getFirst().price().compareTo(new BigDecimal("268.10")) == 0));
        assertThat(result.replacementExternalOrderId()).isEqualTo("REPLACED-1");
    }

    private User user() {
        return new User(USER_ID, "kakao", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);
    }

    private Account account() {
        return new Account(ACCOUNT_ID, USER_ID, "Toss", "1234-56", "app", "secret", "seq-1", Account.Broker.TOSS, null);
    }

    private Strategy strategy() {
        return new Strategy(STRATEGY_ID, ACCOUNT_ID, Strategy.Type.PRIVACY, Strategy.Status.ACTIVE,
                Strategy.Ticker.SOXL, Strategy.CycleSeedType.MAX);
    }

    private StrategyCycle cycle() {
        return new StrategyCycle(CYCLE_ID, STRATEGY_ID, UUID.randomUUID(), new BigDecimal("6989.00"),
                null, LocalDate.of(2026, 6, 21), null, Instant.now(), null);
    }

    private CyclePosition latestPosition() {
        return new CyclePosition(UUID.randomUUID(), CYCLE_ID, new BigDecimal("6665.31"),
                new BigDecimal("266.65"), new BigDecimal("223.41"), 2, Instant.now(), null);
    }

    private Order plannedOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 1), Strategy.Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1,
                new BigDecimal("236.54"), Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order filledOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 1), Strategy.Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 2,
                new BigDecimal("236.54"), Order.OrderStatus.FILLED, "FILLED-1", 2, new BigDecimal("236.54"));
    }

    private Order placedOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 1), Strategy.Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1,
                new BigDecimal("236.54"), Order.OrderStatus.PLACED, "PLACED-1", null, null);
    }

    private AdminOrderCorrectionCommand plannedEditCommand() {
        return new AdminOrderCorrectionCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID, ORDER_ID,
                AdminOrderCorrectionCommand.Mode.PLANNED_EDIT,
                LocalDate.of(2026, 7, 1),
                null,
                3,
                new BigDecimal("250.00"),
                "price fix"
        );
    }

    private AdminOrderCorrectionCommand filledCorrectionCommand() {
        return new AdminOrderCorrectionCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID, ORDER_ID,
                AdminOrderCorrectionCommand.Mode.FILLED_CORRECTION,
                LocalDate.of(2026, 7, 1),
                Order.OrderDirection.SELL,
                2,
                new BigDecimal("267.37"),
                "liquidation correction"
        );
    }

    private AdminOrderCorrectionCommand placedReplaceCommand() {
        return new AdminOrderCorrectionCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID, ORDER_ID,
                AdminOrderCorrectionCommand.Mode.PLACED_REPLACE,
                LocalDate.of(2026, 7, 1),
                null,
                2,
                new BigDecimal("268.10"),
                "replace broker order"
        );
    }
}
