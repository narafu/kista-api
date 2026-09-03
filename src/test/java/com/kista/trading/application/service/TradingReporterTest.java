package com.kista.trading.application.service;

import com.kista.trading.application.event.TradingReportReadyEvent;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.trading.domain.model.BatchContext;
import com.kista.trading.domain.model.StrategyRef;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.broker.domain.model.toss.TossApiException;
import com.kista.sharedkernel.NotificationType;
import com.kista.user.domain.model.User;
import com.kista.user.domain.model.UserSettings;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.broker.application.port.output.ExecutionPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
class TradingReporterTest {

    @Mock BrokerAdapterRegistry registry;
    @Mock ExecutionPort executionPort;
    @Mock OrderPort orderPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock UserSettingsPort userSettingsPort;
    @Mock CyclePositionPersistor cyclePositionPersistor;
    @Mock BrokerOrderCorrectionPort brokerOrderPort;
    TradingReporter reporter;

    static final LocalDate TODAY = LocalDate.of(2026, 7, 9);
    static final BigDecimal CLOSE = new BigDecimal("22.00");

    static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
    static final BrokerAccountRef ACCOUNT_REF = toBrokerRef(ACCOUNT);
    static final StrategyRef STRATEGY = new StrategyRef(
            UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
            StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE
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
    static final BrokerAccountRef TOSS_ACCOUNT_REF = toBrokerRef(TOSS_ACCOUNT);
    static final StrategyRef TOSS_STRATEGY = new StrategyRef(
            UUID.randomUUID(), TOSS_ACCOUNT.id(), StrategyType.INFINITE,
            StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE
    );
    static final StrategyCycle TOSS_CYCLE = new StrategyCycle(
            UUID.randomUUID(), TOSS_STRATEGY.id(), UUID.randomUUID(),
            new BigDecimal("1000.00"), null, TODAY, null, null, null
    );
    static final User TOSS_USER = DomainFixtures.activeUserWithTelegram(TOSS_ACCOUNT.userId());
    static final BatchContext TOSS_CTX = new BatchContext(TOSS_STRATEGY, TOSS_CYCLE, TOSS_ACCOUNT, TOSS_USER);

    @BeforeEach
    void setUp() {
        reporter = new TradingReporter(registry, orderPort, userSettingsPort,
                cyclePositionPersistor, eventPublisher);
        lenient().when(registry.require(ACCOUNT_REF, ExecutionPort.class)).thenReturn(executionPort);
        lenient().when(registry.require(TOSS_ACCOUNT_REF, ExecutionPort.class)).thenReturn(executionPort);
        lenient().when(registry.require(TOSS_ACCOUNT_REF, BrokerOrderCorrectionPort.class)).thenReturn(brokerOrderPort);
        lenient().when(userSettingsPort.findOrDefault(USER.id()))
                .thenReturn(UserSettings.defaultFor(USER.id())); // TRADING_ALERT 기본 활성
        lenient().when(userSettingsPort.findOrDefault(TOSS_USER.id()))
                .thenReturn(UserSettings.defaultFor(TOSS_USER.id()));
    }

    // PLACED 주문 픽스처 — id·externalOrderId 지정 (KIS 계좌/사이클 기준)
    private static Order placedOrder(UUID id, String externalOrderId, int quantity) {
        return new Order(id, ACCOUNT.id(), CYCLE.id(), TODAY, StrategyTicker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                quantity, new BigDecimal("20.00"), Order.OrderStatus.PLACED,
                externalOrderId, null, null);
    }

    // PLACED 주문 픽스처 — Toss 계좌/사이클 기준 (취소 로직 테스트 전용)
    private static Order tossPlacedOrder(UUID id, String externalOrderId, int quantity) {
        return new Order(id, TOSS_ACCOUNT.id(), TOSS_CYCLE.id(), TODAY, StrategyTicker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                quantity, new BigDecimal("20.00"), Order.OrderStatus.PLACED,
                externalOrderId, null, null);
    }

    private static Execution buyExecution(String externalOrderId, int quantity, String price) {
        BigDecimal p = new BigDecimal(price);
        return new Execution(TODAY, StrategyTicker.SOXL, Direction.BUY,
                quantity, p, p.multiply(BigDecimal.valueOf(quantity)), externalOrderId);
    }

    @Test
    void 체결_내역이_없는_PLACED_주문은_CANCELLED_처리된다() {
        UUID unfilledId = UUID.randomUUID();
        UUID filledId = UUID.randomUUID();
        List<Order> orders = List.of(placedOrder(unfilledId, "E-UNFILLED", 5),
                placedOrder(filledId, "E-FILLED", 3));
        when(executionPort.getExecutions(TODAY, TODAY, StrategyTicker.SOXL, ACCOUNT_REF))
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
        when(executionPort.getExecutions(TODAY, TODAY, StrategyTicker.SOXL, ACCOUNT_REF))
                .thenReturn(List.of(buyExecution("E1", 3, "20.00"), buyExecution("E1", 2, "21.00")));

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, orders, null);

        verify(orderPort).markFilled(orderId, 5, new BigDecimal("20.40"), Order.OrderStatus.PARTIALLY_FILLED);
    }

    @Test
    void 체결이_전혀_없어도_미체결_PLACED_주문은_CANCELLED_처리된다() {
        // 버그 수정: executions가 비어도 PLACED 주문은 미체결로 간주해 CANCELLED 처리해야 함
        UUID orderId = UUID.randomUUID();
        List<Order> orders = List.of(placedOrder(orderId, "E1", 5));
        when(executionPort.getExecutions(TODAY, TODAY, StrategyTicker.SOXL, ACCOUNT_REF)).thenReturn(List.of());

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, orders, null);

        verify(orderPort).markCancelled(orderId);
        verify(orderPort, never()).markFilled(any(), anyInt(), any(), any());
    }

    @Test
    void TRADING_ALERT_비활성이면_리포트를_발송하지_않는다() {
        UserSettings muted = mock(UserSettings.class);
        when(muted.isNotificationEnabled(NotificationType.TRADING_ALERT)).thenReturn(false);
        when(userSettingsPort.findOrDefault(USER.id())).thenReturn(muted);
        when(executionPort.getExecutions(TODAY, TODAY, StrategyTicker.SOXL, ACCOUNT_REF)).thenReturn(List.of());

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, List.of(), null);

        ArgumentCaptor<TradingReportReadyEvent> captor = ArgumentCaptor.forClass(TradingReportReadyEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().reportEnabled()).isFalse();
    }

    @Test
    void 체결_건별로_SSE_실시간_알림을_발송한다() {
        when(executionPort.getExecutions(TODAY, TODAY, StrategyTicker.SOXL, ACCOUNT_REF))
                .thenReturn(List.of(buyExecution("E1", 3, "20.00"), buyExecution("E2", 2, "21.00")));

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, List.of(), null);

        ArgumentCaptor<TradingReportReadyEvent> captor = ArgumentCaptor.forClass(TradingReportReadyEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().executions()).hasSize(2);
    }

    @Test
    void 마감_리포트는_체결_조회_전에_Toss_잔여_PLACED_주문을_취소한다() {
        UUID orderId = UUID.randomUUID();
        Order order = tossPlacedOrder(orderId, "E1", 5);
        when(executionPort.getExecutions(TODAY, TODAY, StrategyTicker.SOXL, TOSS_ACCOUNT_REF)).thenReturn(List.of());

        reporter.recordAndNotify(TODAY, TOSS_CTX, BALANCE, CLOSE, List.of(order), null);

        InOrder inOrder = inOrder(brokerOrderPort, executionPort);
        inOrder.verify(brokerOrderPort).cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), TOSS_ACCOUNT_REF);
        inOrder.verify(executionPort).getExecutions(TODAY, TODAY, StrategyTicker.SOXL, TOSS_ACCOUNT_REF);
    }

    @Test
    void Toss_취소_실패는_격리되고_관리자_알림으로_표면화되며_체결조회는_계속된다() {
        UUID orderId = UUID.randomUUID();
        Order order = tossPlacedOrder(orderId, "E1", 5);
        doThrow(new RuntimeException("이미 체결된 주문")).when(brokerOrderPort).cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), TOSS_ACCOUNT_REF);
        when(executionPort.getExecutions(TODAY, TODAY, StrategyTicker.SOXL, TOSS_ACCOUNT_REF))
                .thenReturn(List.of(buyExecution("E1", 5, "20.00")));

        reporter.recordAndNotify(TODAY, TOSS_CTX, BALANCE, CLOSE, List.of(order), null);

        verify(eventPublisher).publishEvent(any(TradingErrorEvent.class));
        verify(orderPort).markFilled(orderId, 5, new BigDecimal("20.00"), Order.OrderStatus.FILLED);
    }

    @Test
    void Toss_취소가_이미체결_409로_거부되면_관리자_알림없이_체결로_기록된다() {
        UUID orderId = UUID.randomUUID();
        Order order = tossPlacedOrder(orderId, "E1", 5);
        doThrow(new TossApiException(
                "Toss API 오류: 409 CONFLICT {\"error\":{\"code\":\"already-filled\",\"message\":\"체결 완료된 주문입니다.\"}}",
                null, TossApiException.Conflict.ALREADY_FILLED)).when(brokerOrderPort).cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), TOSS_ACCOUNT_REF);
        when(executionPort.getExecutions(TODAY, TODAY, StrategyTicker.SOXL, TOSS_ACCOUNT_REF))
                .thenReturn(List.of(buyExecution("E1", 5, "20.00")));

        reporter.recordAndNotify(TODAY, TOSS_CTX, BALANCE, CLOSE, List.of(order), null);

        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
        verify(orderPort).markFilled(orderId, 5, new BigDecimal("20.00"), Order.OrderStatus.FILLED);
    }

    @Test
    void KIS_계좌는_정규장_자동취소되므로_잔여_PLACED_주문_취소_호출을_생략한다() {
        UUID orderId = UUID.randomUUID();
        Order order = placedOrder(orderId, "E1", 5);
        when(executionPort.getExecutions(TODAY, TODAY, StrategyTicker.SOXL, ACCOUNT_REF)).thenReturn(List.of());

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, List.of(order), null);

        verify(brokerOrderPort, never()).cancel(any(), any());
    }

    // broker 모듈 순환 방지 — Account → BrokerAccountRef 변환 (broker는 Account를 직접 참조하지 않음)
    private static BrokerAccountRef toBrokerRef(Account account) {
        return new BrokerAccountRef(
                account.id(), account.appKey(), account.secretKey(),
                account.accountNo(), account.brokerAccountCode(),
                BrokerAccountRef.Broker.valueOf(account.broker().name()));
    }
}
