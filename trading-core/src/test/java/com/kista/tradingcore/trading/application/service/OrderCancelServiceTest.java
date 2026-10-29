package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.support.StubBrokerApiException;
import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.trading.domain.model.CancelResult;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.trading.domain.model.OrderCancelException;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.broker.domain.model.BrokerApiException;
import com.kista.tradingcore.trading.application.event.OrderCancelFailedEvent;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.tradingcore.support.TradingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
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
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderCancelService 단위 테스트")
class OrderCancelServiceTest {

    @Mock OrderPort orderPort;
    @Mock BrokerOrderCorrectionPort brokerPort;
    @Mock AccountPort accountPort;
    @Mock StrategyPort cyclePort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock SubmittingOrderAwaiter awaiter; // 기본 mock은 잔류 없음(빈 목록)
    OrderCancelService service;

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
        ownedAccount = TradingFixtures.kisAccount(accountId, requesterId);
        cycle = new Strategy(cycleId, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        currentCycle = new StrategyCycle(strategyCycleId, cycleId, BigDecimal.valueOf(1000),
                null, LocalDate.now(), null, null, null);
        // @InjectMocks 대신 수동 생성 — stateWriter는 실제 인스턴스에 mock orderPort를 위임해 기존 verify(orderPort) 검증 유지
        service = new OrderCancelService(orderPort, brokerPort, accountPort, cyclePort, strategyCyclePort,
                eventPublisher, new OrderCancelStateWriter(orderPort), awaiter);
        // 취소 범위는 오늘(KST)·다음 거래일 둘 다 — 기본은 오늘 주문 없음, 각 테스트가 다음 거래일(또는 오늘)을 따로 stub
        LocalDate today = LocalDate.now(TimeZones.KST);
        lenient().when(orderPort.deletePlannedByCycleAndDate(any(), eq(today))).thenReturn(0);
        lenient().when(orderPort.findPlacedByCycleAndDate(any(), eq(today))).thenReturn(List.of());
        lenient().when(awaiter.awaitCycle(any(), eq(today))).thenReturn(List.of());
    }

    private static CancelInstruction cancelOf(Order order) {
        return new CancelInstruction(order.ticker(), order.externalOrderId());
    }

    // --- cancelByCycle ---

    @Test
    @DisplayName("cancelByCycle: 오늘 PLANNED 삭제 + PLACED 취소 성공 → CancelResult(n, 0)")
    void cancelByCycle_allSuccess() {
        Order order1 = placedOrder(UUID.randomUUID(), "ORD_1");
        Order order2 = placedOrder(UUID.randomUUID(), "ORD_2");

        when(cyclePort.findByIdOrThrow(cycleId)).thenReturn(cycle);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.deletePlannedByCycleAndDate(eq(strategyCycleId), eq(DstInfo.nextTradeDate())))
                .thenReturn(1); // 실제 삭제 건수로 집계
        when(orderPort.findPlacedByCycleAndDate(eq(strategyCycleId), eq(DstInfo.nextTradeDate())))
                .thenReturn(List.of(order1, order2));

        CancelResult result = service.cancelByCycle(cycleId, requesterId);

        assertThat(result.cancelledCount()).isEqualTo(3);
        assertThat(result.failedCount()).isEqualTo(0);
        verify(orderPort).deletePlannedByCycleAndDate(eq(strategyCycleId), eq(DstInfo.nextTradeDate()));
        verify(brokerPort, times(2)).cancel(any(), eq(ownedAccount.toBrokerRef()));
        verify(orderPort, times(2)).markCancelled(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("cancelByCycle: 오늘(KST) 마감 배치가 접수한 PLACED 주문도 취소한다(04:00~리포트 사이 다음 거래일과 날짜가 갈리는 구간)")
    void cancelByCycle_includesTodayPlacedOrders() {
        Order todayOrder = placedOrder(UUID.randomUUID(), "ORD_TODAY");

        when(cyclePort.findByIdOrThrow(cycleId)).thenReturn(cycle);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.findPlacedByCycleAndDate(eq(strategyCycleId), eq(LocalDate.now(TimeZones.KST))))
                .thenReturn(List.of(todayOrder));

        CancelResult result = service.cancelByCycle(cycleId, requesterId);

        assertThat(result.cancelledCount()).isEqualTo(1);
        verify(brokerPort).cancel(eq(cancelOf(todayOrder)), eq(ownedAccount.toBrokerRef()));
        verify(orderPort).markCancelled(todayOrder.id());
    }

    @Test
    @DisplayName("cancelOpenOrders: 사이클이 없는 전략은 정리할 주문이 없어 CancelResult(0, 0)")
    void cancelOpenOrders_noCycle_returnsEmpty() {
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.empty());

        CancelResult result = service.cancelOpenOrders(cycleId, ownedAccount);

        assertThat(result).isEqualTo(new CancelResult(0, 0));
        verifyNoInteractions(brokerPort);
    }

    @Test
    @DisplayName("cancelByCycle: KIS 취소 일부 실패 시 best-effort → CancelResult(1, 1)")
    void cancelByCycle_partialFailure() {
        Order order1 = placedOrder(UUID.randomUUID(), "ORD_1");
        Order order2 = placedOrder(UUID.randomUUID(), "ORD_2");

        when(cyclePort.findByIdOrThrow(cycleId)).thenReturn(cycle);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.findPlacedByCycleAndDate(eq(strategyCycleId), eq(DstInfo.nextTradeDate())))
                .thenReturn(List.of(order1, order2));
        // order1은 성공, order2는 KIS 오류
        doNothing().when(brokerPort).cancel(eq(cancelOf(order1)), any());
        doThrow(new RuntimeException("KIS 오류")).when(brokerPort).cancel(eq(cancelOf(order2)), any());

        CancelResult result = service.cancelByCycle(cycleId, requesterId);

        assertThat(result.cancelledCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        verify(orderPort, times(1)).markCancelled(order1.id());
        verify(orderPort, never()).markCancelled(order2.id());

        ArgumentCaptor<OrderCancelFailedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCancelFailedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().strategyId()).isEqualTo(cycleId);
        assertThat(eventCaptor.getValue().failedCount()).isEqualTo(1);
        assertThat(eventCaptor.getValue().summary()).contains(order2.id().toString());
    }

    @Test
    @DisplayName("cancelOpenOrders: ALREADY_FILLED 경합은 실패로 세지 않고 알림·markCancelled 없이 PLACED로 둔다")
    void cancelOpenOrders_alreadyFilledConflict_notCountedAsFailure() {
        Order order = placedOrder(UUID.randomUUID(), "ORD_1");
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.findPlacedByCycleAndDate(eq(strategyCycleId), eq(DstInfo.nextTradeDate())))
                .thenReturn(List.of(order));
        doThrow(new StubBrokerApiException("이미 체결", BrokerApiException.Conflict.ALREADY_FILLED))
                .when(brokerPort).cancel(eq(cancelOf(order)), any());

        CancelResult result = service.cancelOpenOrders(cycleId, ownedAccount);

        assertThat(result.failedCount()).isZero();
        verify(orderPort, never()).markCancelled(any());
        verify(eventPublisher, never()).publishEvent(any(OrderCancelFailedEvent.class));
    }

    @Test
    @DisplayName("cancelByCycle: 자격증명 오류 실패는 credentialFailedCount로 따로 세어 재시도 대상에서 뺀다")
    void cancelByCycle_credentialFailure_countedSeparately() {
        Order order = placedOrder(UUID.randomUUID(), "ORD_1");

        when(cyclePort.findByIdOrThrow(cycleId)).thenReturn(cycle);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.findPlacedByCycleAndDate(eq(strategyCycleId), eq(DstInfo.nextTradeDate())))
                .thenReturn(List.of(order));
        doThrow(new BrokerCredentialException()).when(brokerPort).cancel(eq(cancelOf(order)), any());

        CancelResult result = service.cancelByCycle(cycleId, requesterId);

        assertThat(result).isEqualTo(new CancelResult(0, 1, 1));
        assertThat(result.retryableFailedCount()).isZero();
        verify(eventPublisher).publishEvent(any(OrderCancelFailedEvent.class)); // 관리자 알림은 그대로
    }

    @Test
    @DisplayName("cancelByCycle: 브로커가 409(already-canceled)로 거부해도 성공으로 흡수 → CancelResult(2, 0), 알림 없음")
    void cancelByCycle_alreadyCanceledConflict_absorbedAsSuccess() {
        Order order1 = placedOrder(UUID.randomUUID(), "ORD_1");
        Order order2 = placedOrder(UUID.randomUUID(), "ORD_2");

        when(cyclePort.findByIdOrThrow(cycleId)).thenReturn(cycle);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.findPlacedByCycleAndDate(eq(strategyCycleId), eq(DstInfo.nextTradeDate())))
                .thenReturn(List.of(order1, order2));
        // 동일 사이클에 대한 중복 취소 요청(경쟁 상태)의 예상된 결과 — 이미 취소된 주문
        doNothing().when(brokerPort).cancel(eq(cancelOf(order1)), any());
        doThrow(new StubBrokerApiException("Toss API 오류: 409 CONFLICT already-canceled",
                BrokerApiException.Conflict.ALREADY_CANCELED)).when(brokerPort).cancel(eq(cancelOf(order2)), any());

        CancelResult result = service.cancelByCycle(cycleId, requesterId);

        assertThat(result.cancelledCount()).isEqualTo(2);
        assertThat(result.failedCount()).isEqualTo(0);
        verify(orderPort).markCancelled(order1.id());
        verify(orderPort).markCancelled(order2.id());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("cancelByCycle: PLANNED·PLACED 주문 없으면 CancelResult(0, 0)")
    void cancelByCycle_noPlacedOrders() {
        when(cyclePort.findByIdOrThrow(cycleId)).thenReturn(cycle);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.findPlacedByCycleAndDate(eq(strategyCycleId), eq(DstInfo.nextTradeDate())))
                .thenReturn(List.of());

        CancelResult result = service.cancelByCycle(cycleId, requesterId);

        assertThat(result.cancelledCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        verifyNoInteractions(brokerPort);
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

        verify(brokerPort).cancel(cancelOf(order), ownedAccount.toBrokerRef());
        verify(orderPort).markCancelled(orderId);
    }

    @Test
    @DisplayName("cancelOrder: PLANNED 주문은 증권사 취소 없이 DB 취소만 수행")
    void cancelOrder_planned_success() {
        Order order = plannedOrder(orderId);
        when(orderPort.findById(orderId)).thenReturn(Optional.of(order));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);

        service.cancelOrder(orderId, requesterId);

        verify(orderPort).cancelPlanned(orderId); // PLANNED일 때만 조건부 전이
        verify(orderPort, never()).markCancelled(any());
        verifyNoInteractions(brokerPort);
    }

    @Test
    @DisplayName("cancelOrder: PLANNED로 읽었는데 그 사이 배치가 접수했으면 증권사 취소로 이어간다")
    void cancelOrder_planned_placedMeanwhile_cancelsAtBroker() {
        Order placed = placedOrder(orderId, "ORD_RACE");
        when(orderPort.findById(orderId)).thenReturn(Optional.of(plannedOrder(orderId)), Optional.of(placed));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        doThrow(new OrderStateConflictException("경합")).when(orderPort).cancelPlanned(orderId);

        service.cancelOrder(orderId, requesterId);

        verify(brokerPort).cancel(cancelOf(placed), ownedAccount.toBrokerRef());
        verify(orderPort).markCancelled(orderId);
    }

    @Test
    @DisplayName("cancelOrder: PLANNED로 읽었는데 그 사이 행이 정리됐으면 409")
    void cancelOrder_planned_deletedMeanwhile_conflict() {
        when(orderPort.findById(orderId)).thenReturn(Optional.of(plannedOrder(orderId)), Optional.empty());
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        doThrow(new OrderStateConflictException("경합")).when(orderPort).cancelPlanned(orderId);

        assertThatThrownBy(() -> service.cancelOrder(orderId, requesterId))
                .isInstanceOf(OrderCancelException.class);
        verifyNoInteractions(brokerPort);
    }

    @Test
    @DisplayName("cancelOrder: 브로커가 409(already-canceled)로 거부해도 성공으로 흡수해 DB만 CANCELLED 처리")
    void cancelOrder_alreadyCanceledConflict_absorbedAsSuccess() {
        Order order = placedOrder(orderId, "ORD_99");
        when(orderPort.findById(orderId)).thenReturn(Optional.of(order));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        doThrow(new StubBrokerApiException("Toss API 오류: 409 CONFLICT already-canceled",
                BrokerApiException.Conflict.ALREADY_CANCELED)).when(brokerPort).cancel(cancelOf(order), ownedAccount.toBrokerRef());

        service.cancelOrder(orderId, requesterId);

        verify(orderPort).markCancelled(orderId);
    }

    @Test
    @DisplayName("cancelOrder: 브로커가 ALREADY_FILLED 경합을 던지면 OrderCancelException, markCancelled 안 함")
    void cancelOrder_alreadyFilledConflict_throwsOrderCancelException() {
        Order order = placedOrder(orderId, "ORD_99");
        when(orderPort.findById(orderId)).thenReturn(Optional.of(order));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        doThrow(new StubBrokerApiException("이미 체결", BrokerApiException.Conflict.ALREADY_FILLED))
                .when(brokerPort).cancel(cancelOf(order), ownedAccount.toBrokerRef());

        assertThatThrownBy(() -> service.cancelOrder(orderId, requesterId))
                .isInstanceOf(OrderCancelException.class)
                .hasMessageContaining("이미 체결");
        verify(orderPort, never()).markCancelled(any());
    }

    @Test
    @DisplayName("cancelOrder: 브로커 취소가 그 외 사유로 실패하면 예외가 그대로 전파된다")
    void cancelOrder_otherBrokerFailure_propagates() {
        Order order = placedOrder(orderId, "ORD_99");
        when(orderPort.findById(orderId)).thenReturn(Optional.of(order));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        doThrow(new RuntimeException("네트워크 오류")).when(brokerPort).cancel(cancelOf(order), ownedAccount.toBrokerRef());

        assertThatThrownBy(() -> service.cancelOrder(orderId, requesterId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("네트워크 오류");
        verify(orderPort, never()).markCancelled(orderId);
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
        Order filledOrder = new Order(orderId, accountId, strategyCycleId, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, BigDecimal.valueOf(5), BigDecimal.valueOf(25),
                OrderStatus.FILLED, "ORD_99", null, null);
        when(orderPort.findById(orderId)).thenReturn(Optional.of(filledOrder));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);

        assertThatThrownBy(() -> service.cancelOrder(orderId, requesterId))
                .isInstanceOf(OrderCancelException.class);
    }

    // --- 트랜잭션 분리 검증 ---

    @Test
    @DisplayName("OrderCancelService는 클래스·메서드 어디에도 @Transactional이 없다 — 브로커 취소 HTTP가 트랜잭션 밖에서 실행됨")
    void orderCancelService_hasNoTransactionalAnnotation() throws NoSuchMethodException {
        assertThat(OrderCancelService.class.isAnnotationPresent(Transactional.class)).isFalse();

        Method cancelByCycle = OrderCancelService.class.getDeclaredMethod("cancelByCycle", UUID.class, UUID.class);
        Method cancelOrder = OrderCancelService.class.getDeclaredMethod("cancelOrder", UUID.class, UUID.class);
        assertThat(cancelByCycle.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(cancelOrder.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("OrderCancelStateWriter의 DB 쓰기 메서드에는 @Transactional이 있다")
    void orderCancelStateWriter_dbWritesAreTransactional() throws NoSuchMethodException {
        Method deletePlanned = OrderCancelStateWriter.class.getDeclaredMethod("deletePlanned", UUID.class, LocalDate.class);
        Method markCancelled = OrderCancelStateWriter.class.getDeclaredMethod("markCancelled", UUID.class);
        assertThat(deletePlanned.isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(markCancelled.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    @DisplayName("cancelOrder: 브로커 취소 성공 이후에만 markCancelled가 호출된다")
    void cancelOrder_marksCancelledOnlyAfterBrokerCancelSucceeds() {
        Order order = placedOrder(orderId, "ORD_99");
        when(orderPort.findById(orderId)).thenReturn(Optional.of(order));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);

        service.cancelOrder(orderId, requesterId);

        InOrder inOrder = inOrder(brokerPort, orderPort);
        inOrder.verify(brokerPort).cancel(cancelOf(order), ownedAccount.toBrokerRef());
        inOrder.verify(orderPort).markCancelled(orderId);
    }

    // ---

    private Order placedOrder(UUID id, String externalOrderId) {
        return new Order(id, accountId, strategyCycleId, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, BigDecimal.valueOf(5), BigDecimal.valueOf(25),
                OrderStatus.PLACED, externalOrderId, null, null);
    }

    private Order plannedOrder(UUID id) {
        return new Order(id, accountId, strategyCycleId, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, BigDecimal.valueOf(5), BigDecimal.valueOf(25),
                OrderStatus.PLANNED, null, null, null);
    }

    // --- 접수 중(SUBMITTING) 주문 — spec 2026-10-16 §3.6 ---

    private Order submittingOrder(UUID id) {
        return new Order(id, accountId, strategyCycleId, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, BigDecimal.valueOf(5), BigDecimal.valueOf(25),
                OrderStatus.SUBMITTING, null, null, null);
    }

    @Test
    @DisplayName("cancelOpenOrders: 접수 중 주문은 PLANNED 삭제 후 대기 → PLACED가 되면 PLACED 조회에 합류해 증권사 취소")
    void cancelOpenOrders_submittingSettlesToPlaced_cancelsAtBroker() {
        Order settled = placedOrder(UUID.randomUUID(), "ORD_SETTLED");
        LocalDate next = DstInfo.nextTradeDate();
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.deletePlannedByCycleAndDate(strategyCycleId, next)).thenReturn(0);
        when(awaiter.awaitCycle(strategyCycleId, next)).thenReturn(List.of());
        when(orderPort.findPlacedByCycleAndDate(strategyCycleId, next)).thenReturn(List.of(settled));

        CancelResult result = service.cancelOpenOrders(cycleId, ownedAccount);

        InOrder inOrder = inOrder(orderPort, awaiter);
        inOrder.verify(orderPort).deletePlannedByCycleAndDate(strategyCycleId, next);
        inOrder.verify(awaiter).awaitCycle(strategyCycleId, next);
        inOrder.verify(orderPort).findPlacedByCycleAndDate(strategyCycleId, next);
        verify(brokerPort).cancel(cancelOf(settled), ownedAccount.toBrokerRef());
        assertThat(result.cancelledCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelOpenOrders: 대기 후에도 접수 중이면 재시도 가능한 실패로 집계 + 관리자 알림")
    void cancelOpenOrders_stillSubmitting_countsRetryableFailure() {
        Order stuck = submittingOrder(UUID.randomUUID());
        LocalDate next = DstInfo.nextTradeDate();
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.deletePlannedByCycleAndDate(strategyCycleId, next)).thenReturn(0);
        when(awaiter.awaitCycle(strategyCycleId, next)).thenReturn(List.of(stuck));
        when(orderPort.findPlacedByCycleAndDate(strategyCycleId, next)).thenReturn(List.of());

        CancelResult result = service.cancelOpenOrders(cycleId, ownedAccount);

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.retryableFailedCount()).isEqualTo(1);
        ArgumentCaptor<OrderCancelFailedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCancelFailedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().summary()).contains(stuck.id().toString()).contains("접수 결과 미확정");
    }

    @Test
    @DisplayName("cancelOrder: 접수 중이면 기다렸다가 PLACED가 되면 증권사 취소")
    void cancelOrder_submitting_waitsThenCancels() {
        Order submitting = submittingOrder(orderId);
        Order placed = submitting.withPlaced("ORD_LATE");
        when(orderPort.findById(orderId)).thenReturn(Optional.of(submitting));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        when(awaiter.awaitOrder(submitting)).thenReturn(placed);

        service.cancelOrder(orderId, requesterId);

        verify(brokerPort).cancel(cancelOf(placed), ownedAccount.toBrokerRef());
        verify(orderPort).markCancelled(orderId);
    }

    @Test
    @DisplayName("cancelOrder: 대기 후에도 접수 중이면 다시 시도 안내")
    void cancelOrder_stillSubmitting_throws() {
        Order submitting = submittingOrder(orderId);
        when(orderPort.findById(orderId)).thenReturn(Optional.of(submitting));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(ownedAccount);
        when(awaiter.awaitOrder(submitting)).thenReturn(submitting);

        assertThatThrownBy(() -> service.cancelOrder(orderId, requesterId))
                .isInstanceOf(OrderCancelException.class)
                .hasMessage("주문 접수를 처리하는 중입니다. 잠시 후 다시 시도해 주세요.");
        verify(brokerPort, never()).cancel(any(), any());
    }

    // --- 코인(UPBIT) 계좌: 시장가 주문은 즉시 체결되므로 주식 브로커 취소를 호출하지 않는다 ---

    private Order cryptoOrder(OrderStatus status) {
        return new Order(UUID.randomUUID(), accountId, strategyCycleId, LocalDate.now(), StrategyTicker.BTC,
                OrderType.MARKET, OrderTiming.AT_CLOSE, OrderDirection.BUY, new BigDecimal("0.00012345"),
                BigDecimal.valueOf(100_000_000), status, "upbit-uuid", null, null);
    }

    @Test
    @DisplayName("accountDeletionWithPlacedCryptoOrderDoesNotCallBrokerCancel: 체결 확인 중 코인 주문이 있으면 아무것도 지우지 않고 거부한다")
    void accountDeletionWithPlacedCryptoOrderDoesNotCallBrokerCancel() {
        Account upbit = TradingFixtures.upbitAccount(accountId, requesterId);
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.findOpenByAccountId(accountId)).thenReturn(List.of(cryptoOrder(OrderStatus.PLACED)));

        assertThatThrownBy(() -> service.cancelOpenOrders(cycleId, upbit))
                .isInstanceOf(OrderCancelException.class).hasMessageContaining("체결 확인 중");
        verify(orderPort, never()).deletePlannedByCycleAndDate(any(), any());
        verify(orderPort, never()).findPlacedByCycleAndDate(any(), any());
        verifyNoInteractions(brokerPort, awaiter);
    }

    @Test
    @DisplayName("코인 계좌에 PLANNED만 있으면 삭제하고 진행 — 브로커 취소·대기 없음")
    void cryptoOnlyPlanned_deletesAndProceeds() {
        Account upbit = TradingFixtures.upbitAccount(accountId, requesterId);
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
        when(orderPort.findOpenByAccountId(accountId)).thenReturn(List.of(cryptoOrder(OrderStatus.PLANNED)));
        when(orderPort.deletePlannedByCycleAndDate(eq(strategyCycleId), any())).thenReturn(1);

        CancelResult result = service.cancelOpenOrders(cycleId, upbit);

        assertThat(result.failedCount()).isZero();
        assertThat(result.cancelledCount()).isPositive();
        verify(orderPort, never()).findPlacedByCycleAndDate(any(), any());
        verifyNoInteractions(brokerPort, awaiter, eventPublisher);
    }

    @Test
    @DisplayName("requireNoCryptoInFlight: 주식 계좌는 조회 없이 통과")
    void requireNoCryptoInFlight_stockAccount_noop() {
        service.requireNoCryptoInFlight(cycleId, ownedAccount);
        verifyNoInteractions(orderPort);
    }

    @Test
    @DisplayName("cancelOrder: 코인 PLANNED 단건 취소는 cancelPlanned로 처리")
    void cancelOrder_cryptoPlanned_cancelPlanned() {
        Order order = cryptoOrder(OrderStatus.PLANNED);
        when(orderPort.findById(order.id())).thenReturn(Optional.of(order));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(TradingFixtures.upbitAccount(accountId, requesterId));

        service.cancelOrder(order.id(), requesterId);

        verify(orderPort).cancelPlanned(order.id());
        verifyNoInteractions(brokerPort);
    }

    @Test
    @DisplayName("cancelOrder: 코인 PLACED 주문 단건 취소는 브로커 취소 없이 OrderCancelException")
    void cancelOrder_cryptoPlaced_rejectedWithoutBrokerCall() {
        Order order = cryptoOrder(OrderStatus.PLACED);
        when(orderPort.findById(order.id())).thenReturn(Optional.of(order));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(TradingFixtures.upbitAccount(accountId, requesterId));

        assertThatThrownBy(() -> service.cancelOrder(order.id(), requesterId)).isInstanceOf(OrderCancelException.class);
        verifyNoInteractions(brokerPort, awaiter);
    }
}

