package com.kista.application.service.admin;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminReorderCommand;
import com.kista.domain.model.admin.AdminReorderResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserPort;
import com.kista.domain.port.out.broker.BrokerOrderCorrectionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminReorderServiceTest {

    @Mock UserPort userPort;
    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock OrderPort orderPort;
    @Mock AuditLogPort auditLogPort;
    @Mock BrokerAdapterRegistry brokerAdapterRegistry;
    @Mock BrokerOrderCorrectionPort brokerOrderCorrectionPort;

    @InjectMocks AdminReorderService service;

    private static final UUID ADMIN_ID   = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_ID    = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID STRATEGY_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID CYCLE_ID   = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID ORDER_ID   = UUID.fromString("00000000-0000-0000-0000-000000000050");

    // 시장 단계별 테스트 상수 (DST=true 기준, 미국 EDT)
    // marketOpen = 22:30 KST = 13:30 UTC
    // marketClose = 05:00 KST = 20:00 UTC (전날)
    // BLOCKED = [05:00, 17:00) KST = [20:00 UTC D-1, 08:00 UTC D)

    // 개장 전 (DIRECT 시간대, 19:00 KST = 10:00 UTC — before 22:30 open)
    private static final DstInfo DST_FOR_TEST = new DstInfo(true,
            Instant.parse("2026-07-02T19:30:00Z"),  // orderAt (무관)
            Instant.parse("2026-07-02T20:10:00Z"),  // postClose (무관)
            Instant.parse("2026-07-01T13:30:00Z")); // marketOpen = 22:30 KST July 1
    private static final Instant NOW_BEFORE_OPEN = Instant.parse("2026-07-01T10:00:00Z"); // 19:00 KST July 1

    // 정규장 중 (DIRECT 시간대, 02:00 KST July 2 = 17:00 UTC July 1 — after 22:30 open)
    private static final Instant NOW_DURING_MARKET = Instant.parse("2026-07-01T17:00:00Z"); // 02:00 KST July 2

    // 장 마감 후 (BLOCKED, 07:00 KST July 2 = 22:00 UTC July 1 — between close 05:00 and premarket 17:00 KST)
    private static final Instant NOW_AFTER_CLOSE = Instant.parse("2026-07-01T22:00:00Z"); // 07:00 KST July 2

    // --- 상태별 취소 + PLANNED 저장 ---

    @Test
    void reorder_fromPlanned_cancelsThenSavesPlanned() {
        stubCommon(plannedOrder());

        AdminReorderResult result = reorder(command(Order.OrderTiming.AT_CLOSE), NOW_BEFORE_OPEN);

        verify(orderPort).markCancelled(ORDER_ID);
        verify(orderPort).saveAll(argOrdersMatch(Order.OrderStatus.PLANNED, Order.OrderTiming.AT_CLOSE));
        assertThat(result.originalStatus()).isEqualTo(Order.OrderStatus.PLANNED);
        assertThat(result.resultingStatus()).isEqualTo(Order.OrderStatus.PLANNED);
    }

    @Test
    void reorder_fromPlaced_cancelsBrokerThenSavesPlanned() {
        stubCommon(placedOrder());
        when(brokerAdapterRegistry.require(account(), BrokerOrderCorrectionPort.class)).thenReturn(brokerOrderCorrectionPort);

        reorder(command(Order.OrderTiming.AT_CLOSE), NOW_BEFORE_OPEN);

        verify(brokerOrderCorrectionPort).cancel(placedOrder(), account()); // 증권사 취소
        verify(orderPort).markCancelled(ORDER_ID);
        verify(orderPort).saveAll(argOrdersMatch(Order.OrderStatus.PLANNED, Order.OrderTiming.AT_CLOSE));
    }

    @Test
    void reorder_fromFilled_noCancel_savesPlanned() {
        stubCommon(filledOrder());

        reorder(command(Order.OrderTiming.AT_OPEN), NOW_BEFORE_OPEN);

        verify(orderPort, never()).markCancelled(any()); // 체결 주문 취소 없음
        verify(orderPort).saveAll(argOrdersMatch(Order.OrderStatus.PLANNED, Order.OrderTiming.AT_OPEN));
    }

    @Test
    void reorder_fromFailed_noCancel_savesPlanned() {
        stubCommon(failedOrder());

        reorder(command(Order.OrderTiming.AT_OPEN), NOW_BEFORE_OPEN);

        verify(orderPort, never()).markCancelled(any());
        verify(orderPort).saveAll(argOrdersMatch(Order.OrderStatus.PLANNED, Order.OrderTiming.AT_OPEN));
    }

    // --- IMMEDIATE 접수 ---

    @Test
    void reorder_immediate_success_savesPlaced() {
        stubCommon(plannedOrder());
        when(brokerAdapterRegistry.require(account(), BrokerOrderCorrectionPort.class)).thenReturn(brokerOrderCorrectionPort);
        when(brokerOrderCorrectionPort.place(any(), any()))
                .thenAnswer(inv -> ((Order) inv.getArgument(0)).withPlaced("NEW-EXT-1"));

        AdminReorderResult result = reorder(command(Order.OrderTiming.IMMEDIATE), NOW_DURING_MARKET);

        verify(orderPort).saveAll(argOrdersMatch(Order.OrderStatus.PLACED, Order.OrderTiming.IMMEDIATE));
        assertThat(result.resultingStatus()).isEqualTo(Order.OrderStatus.PLACED);
        assertThat(result.newOrderExternalId()).isEqualTo("NEW-EXT-1");
    }

    @Test
    void reorder_immediate_brokerError_savesFailed() {
        stubCommon(plannedOrder());
        when(brokerAdapterRegistry.require(account(), BrokerOrderCorrectionPort.class)).thenReturn(brokerOrderCorrectionPort);
        when(brokerOrderCorrectionPort.place(any(), any())).thenThrow(new RuntimeException("증권사 오류"));

        AdminReorderResult result = reorder(command(Order.OrderTiming.IMMEDIATE), NOW_DURING_MARKET);

        verify(orderPort).saveAll(argOrdersMatch(Order.OrderStatus.FAILED, Order.OrderTiming.IMMEDIATE));
        assertThat(result.resultingStatus()).isEqualTo(Order.OrderStatus.FAILED);
    }

    // --- 시점 가용성 검증 ---

    @Test
    void reorder_immediateWhenClosed_throwsIllegalArgument() {
        stubCommon(plannedOrder());

        assertThatThrownBy(() -> reorder(command(Order.OrderTiming.IMMEDIATE), NOW_AFTER_CLOSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IMMEDIATE");
    }

    @Test
    void reorder_atOpenAfterMarketOpen_throwsIllegalArgument() {
        stubCommon(plannedOrder());

        // NOW_DURING_MARKET = 정규장 중 → AT_OPEN 불가 (개장 이후)
        assertThatThrownBy(() -> reorder(command(Order.OrderTiming.AT_OPEN), NOW_DURING_MARKET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AT_OPEN");
    }

    @Test
    void reorder_allTimingsWhenClosed_allThrow() {
        stubCommon(plannedOrder());

        for (Order.OrderTiming timing : Order.OrderTiming.values()) {
            assertThatThrownBy(() -> reorder(command(timing), NOW_AFTER_CLOSE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- 헬퍼 ---

    private AdminReorderResult reorder(AdminReorderCommand command, Instant now) {
        return service.reorder(ADMIN_ID, command, DST_FOR_TEST, now);
    }

    private void stubCommon(Order order) {
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user());
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy());
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(cycle()));
        when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Order> argOrdersMatch(Order.OrderStatus status, Order.OrderTiming timing) {
        return org.mockito.ArgumentMatchers.argThat(orders ->
                ((java.util.List<Order>) orders).size() == 1
                && ((java.util.List<Order>) orders).get(0).status() == status
                && ((java.util.List<Order>) orders).get(0).timing() == timing);
    }

    private User user() {
        return new User(USER_ID, "kakao", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);
    }

    private Account account() {
        return new Account(ACCOUNT_ID, USER_ID, "Toss", "1234-56", "app", "secret", "seq-1",
                Account.Broker.TOSS, null);
    }

    private Strategy strategy() {
        return new Strategy(STRATEGY_ID, ACCOUNT_ID, Strategy.Type.PRIVACY, Strategy.Status.ACTIVE,
                Strategy.Ticker.SOXL, Strategy.CycleSeedType.MAX);
    }

    private StrategyCycle cycle() {
        return new StrategyCycle(CYCLE_ID, STRATEGY_ID, UUID.randomUUID(), new BigDecimal("6989.00"),
                null, LocalDate.of(2026, 6, 21), null, Instant.now(), null);
    }

    private Order plannedOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 1), Strategy.Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1,
                new BigDecimal("236.54"), Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order placedOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 1), Strategy.Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1,
                new BigDecimal("236.54"), Order.OrderStatus.PLACED, "PLACED-1", null, null);
    }

    private Order filledOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 1), Strategy.Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 2,
                new BigDecimal("236.54"), Order.OrderStatus.FILLED, "FILLED-1", 2, new BigDecimal("236.54"));
    }

    private Order failedOrder() {
        return new Order(ORDER_ID, ACCOUNT_ID, CYCLE_ID, LocalDate.of(2026, 7, 1), Strategy.Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1,
                new BigDecimal("236.54"), Order.OrderStatus.FAILED, null, null, null);
    }

    private AdminReorderCommand command(Order.OrderTiming timing) {
        return new AdminReorderCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID, ORDER_ID,
                timing,
                LocalDate.of(2026, 7, 1),
                null,
                2,
                new BigDecimal("250.00"),
                "reorder memo"
        );
    }
}
