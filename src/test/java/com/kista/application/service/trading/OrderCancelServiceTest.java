package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.CancelResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.OrderCancelException;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderCancelService 단위 테스트")
class OrderCancelServiceTest {

    @Mock OrderPort orderPort;
    @Mock BrokerOrderRouter brokerOrderRouter;
    @Mock AccountPort accountPort;
    @Mock StrategyPort cyclePort;
    @Mock StrategyCyclePort strategyCyclePort;
    @InjectMocks OrderCancelService service;

    private final UUID requesterId = UUID.randomUUID();
    private final UUID cycleId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID strategyCycleId = UUID.randomUUID();

    private Account ownedAccount;
    private Strategy cycle;
    private StrategyCycle currentCycle;

    @BeforeEach
    void setUp() {
        ownedAccount = new Account(accountId, requesterId, "테스트계좌",
                "74420614", "appKey", "appSecret", "01", Account.Broker.KIS);
        cycle = new Strategy(cycleId, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE, 20);
        currentCycle = new StrategyCycle(strategyCycleId, cycleId, BigDecimal.valueOf(1000),
                null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED);
    }

    // --- cancelByCycle ---

    @Test
    @DisplayName("cancelByCycle: 오늘 PLACED 주문 모두 취소 성공 → CancelResult(n, 0)")
    void cancelByCycle_allSuccess() {
        Order order1 = placedOrder(UUID.randomUUID(), "ORD_1");
        Order order2 = placedOrder(UUID.randomUUID(), "ORD_2");

        when(cyclePort.findByIdOrThrow(cycleId)).thenReturn(cycle);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.findPlacedByCycleAndDate(eq(strategyCycleId), any(LocalDate.class)))
                .thenReturn(List.of(order1, order2));

        CancelResult result = service.cancelByCycle(cycleId, requesterId);

        assertThat(result.cancelledCount()).isEqualTo(2);
        assertThat(result.failedCount()).isEqualTo(0);
        verify(brokerOrderRouter, times(2)).cancel(any(), eq(ownedAccount));
        verify(orderPort, times(2)).markCancelled(any());
    }

    @Test
    @DisplayName("cancelByCycle: KIS 취소 일부 실패 시 best-effort → CancelResult(1, 1)")
    void cancelByCycle_partialFailure() {
        Order order1 = placedOrder(UUID.randomUUID(), "ORD_1");
        Order order2 = placedOrder(UUID.randomUUID(), "ORD_2");

        when(cyclePort.findByIdOrThrow(cycleId)).thenReturn(cycle);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.findPlacedByCycleAndDate(eq(strategyCycleId), any(LocalDate.class)))
                .thenReturn(List.of(order1, order2));
        // order1은 성공, order2는 KIS 오류
        doNothing().when(brokerOrderRouter).cancel(eq(order1), any());
        doThrow(new RuntimeException("KIS 오류")).when(brokerOrderRouter).cancel(eq(order2), any());

        CancelResult result = service.cancelByCycle(cycleId, requesterId);

        assertThat(result.cancelledCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        verify(orderPort, times(1)).markCancelled(order1.id());
        verify(orderPort, never()).markCancelled(order2.id());
    }

    @Test
    @DisplayName("cancelByCycle: PLACED 주문 없으면 CancelResult(0, 0)")
    void cancelByCycle_noPlacedOrders() {
        when(cyclePort.findByIdOrThrow(cycleId)).thenReturn(cycle);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.findPlacedByCycleAndDate(eq(strategyCycleId), any(LocalDate.class)))
                .thenReturn(List.of());

        CancelResult result = service.cancelByCycle(cycleId, requesterId);

        assertThat(result.cancelledCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        verifyNoInteractions(brokerOrderRouter);
    }

    @Test
    @DisplayName("cancelByCycle: 소유권 불일치 → SecurityException")
    void cancelByCycle_ownershipMismatch_throwsSecurityException() {
        UUID otherUser = UUID.randomUUID();
        when(cyclePort.findByIdOrThrow(cycleId)).thenReturn(cycle);
        when(accountPort.requireOwnedAccount(accountId, otherUser))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        assertThatThrownBy(() -> service.cancelByCycle(cycleId, otherUser))
                .isInstanceOf(SecurityException.class);
    }

    // --- cancelOrder ---

    @Test
    @DisplayName("cancelOrder: PLACED 주문 취소 성공")
    void cancelOrder_success() {
        Order order = placedOrder(orderId, "ORD_99");
        when(orderPort.findById(orderId)).thenReturn(Optional.of(order));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);

        service.cancelOrder(orderId, requesterId);

        verify(brokerOrderRouter).cancel(order, ownedAccount);
        verify(orderPort).markCancelled(orderId);
    }

    @Test
    @DisplayName("cancelOrder: 주문 없으면 NoSuchElementException")
    void cancelOrder_notFound() {
        when(orderPort.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOrder(orderId, requesterId))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("cancelOrder: 소유권 불일치 → SecurityException")
    void cancelOrder_ownershipMismatch() {
        Order order = placedOrder(orderId, "ORD_99");
        UUID otherUser = UUID.randomUUID();
        when(orderPort.findById(orderId)).thenReturn(Optional.of(order));
        when(accountPort.requireOwnedAccount(accountId, otherUser))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        assertThatThrownBy(() -> service.cancelOrder(orderId, otherUser))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("cancelOrder: PLACED가 아닌 상태 → OrderCancelException(409)")
    void cancelOrder_notPlaced_throwsIllegalStateException() {
        Order filledOrder = new Order(orderId, accountId, strategyCycleId, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 5, BigDecimal.valueOf(25),
                Order.OrderStatus.FILLED, "ORD_99", null, null);
        when(orderPort.findById(orderId)).thenReturn(Optional.of(filledOrder));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);

        assertThatThrownBy(() -> service.cancelOrder(orderId, requesterId))
                .isInstanceOf(OrderCancelException.class);
    }

    // ---

    private Order placedOrder(UUID id, String externalOrderId) {
        return new Order(id, accountId, strategyCycleId, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 5, BigDecimal.valueOf(25),
                Order.OrderStatus.PLACED, externalOrderId, null, null);
    }
}
