package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.SubmittingOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

// 취소 경로의 접수 중(SUBMITTING) 주문 유한 대기 (spec 2026-10-16 §3.6)
@ExtendWith(MockitoExtension.class)
@DisplayName("SubmittingOrderAwaiter 단위 테스트")
class SubmittingOrderAwaiterTest {

    @Mock OrderPort orderPort;

    static final UUID CYCLE_ID = UUID.randomUUID();
    static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 17);
    static final Order ORDER = new Order(UUID.randomUUID(), UUID.randomUUID(), CYCLE_ID, TRADE_DATE,
            StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, BigDecimal.valueOf(3),
            new BigDecimal("20.00"), OrderStatus.SUBMITTING, null, null, null);
    SubmittingOrder LEFTOVER = new SubmittingOrder(ORDER, Instant.now()); // 테스트마다 갓 선점된 잔류(대기 상한 이내)

    private SubmittingOrderAwaiter awaiter() {
        return new SubmittingOrderAwaiter(orderPort, Duration.ofMillis(200), Duration.ofMillis(10));
    }

    @Test
    @DisplayName("접수 중 주문이 없으면 대기 없이 즉시 빈 목록")
    void awaitCycle_noSubmitting_returnsImmediately() {
        when(orderPort.findSubmittingByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of());

        assertThat(awaiter().awaitCycle(CYCLE_ID, TRADE_DATE)).isEmpty();
        verify(orderPort, times(1)).findSubmittingByCycleAndDate(CYCLE_ID, TRADE_DATE);
    }

    @Test
    @DisplayName("대기 중 접수가 끝나면 빈 목록")
    void awaitCycle_settlesDuringWait() {
        when(orderPort.findSubmittingByCycleAndDate(CYCLE_ID, TRADE_DATE))
                .thenReturn(List.of(LEFTOVER)).thenReturn(List.of());

        assertThat(awaiter().awaitCycle(CYCLE_ID, TRADE_DATE)).isEmpty();
    }

    @Test
    @DisplayName("타임아웃까지 남아 있으면 그 주문을 반환")
    void awaitCycle_timeout_returnsRemaining() {
        when(orderPort.findSubmittingByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(LEFTOVER));

        assertThat(awaiter().awaitCycle(CYCLE_ID, TRADE_DATE)).extracting(Order::id).containsExactly(ORDER.id());
    }

    @Test
    @DisplayName("단건 대기 — PLACED가 되면 최신 주문 반환")
    void awaitOrder_becomesPlaced() {
        when(orderPort.findSubmittingByCycleAndDate(CYCLE_ID, TRADE_DATE))
                .thenReturn(List.of(LEFTOVER)).thenReturn(List.of());
        when(orderPort.findById(ORDER.id())).thenReturn(Optional.of(ORDER.withPlaced("EXT")));

        Order latest = awaiter().awaitOrder(ORDER);

        assertThat(latest.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(latest.externalOrderId()).isEqualTo("EXT");
    }

    @Test
    @DisplayName("단건 대기 — 타임아웃이면 SUBMITTING 그대로 반환")
    void awaitOrder_timeout_returnsSubmitting() {
        when(orderPort.findSubmittingByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(LEFTOVER));
        when(orderPort.findById(ORDER.id())).thenReturn(Optional.of(ORDER));

        assertThat(awaiter().awaitOrder(ORDER).status()).isEqualTo(OrderStatus.SUBMITTING);
    }

    @Test
    @DisplayName("선점 후 대기 상한보다 오래된 잔류는 기다리지 않고 즉시 반환 — 런북 정정 전까지 매 취소가 묶이지 않는다")
    void awaitCycle_staleLeftover_returnsImmediately() {
        SubmittingOrder stale = new SubmittingOrder(ORDER, Instant.now().minus(Duration.ofHours(1)));
        when(orderPort.findSubmittingByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(stale));
        SubmittingOrderAwaiter longWait = new SubmittingOrderAwaiter(orderPort, Duration.ofSeconds(60), Duration.ofMillis(10));

        long start = System.nanoTime();
        List<Order> remaining = longWait.awaitCycle(CYCLE_ID, TRADE_DATE);

        assertThat(remaining).extracting(Order::id).containsExactly(ORDER.id());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        verify(orderPort, times(1)).findSubmittingByCycleAndDate(CYCLE_ID, TRADE_DATE);
    }

    @Test
    @DisplayName("단건 — 오래된 잔류는 기다리지 않고 SUBMITTING 즉시 반환")
    void awaitOrder_staleLeftover_returnsImmediately() {
        SubmittingOrder stale = new SubmittingOrder(ORDER, Instant.now().minus(Duration.ofHours(1)));
        when(orderPort.findSubmittingByCycleAndDate(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(stale));
        when(orderPort.findById(ORDER.id())).thenReturn(Optional.of(ORDER));
        SubmittingOrderAwaiter longWait = new SubmittingOrderAwaiter(orderPort, Duration.ofSeconds(60), Duration.ofMillis(10));

        long start = System.nanoTime();
        assertThat(longWait.awaitOrder(ORDER).status()).isEqualTo(OrderStatus.SUBMITTING);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }
}
