package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class OrderPersistenceAdapterTest {

    @Mock OrderJpaRepository repository;
    @Mock EntityManager entityManager; // refresh는 no-op — 엔티티 상태는 테스트가 직접 세팅
    OrderPersistenceAdapter adapter;

    static final UUID ACCOUNT_ID = UUID.randomUUID();
    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();
    static final LocalDate TODAY = LocalDate.now();
    static final BigDecimal PRICE = new BigDecimal("22.0000");

    @BeforeEach
    void setUp() {
        adapter = new OrderPersistenceAdapter(repository, entityManager);
    }

    @Test
    void saveAll_delegatesToRepository() {
        Order order = new Order(null, ACCOUNT_ID, STRATEGY_CYCLE_ID, TODAY, StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, 5, PRICE,
                OrderStatus.PLANNED, null, null, null);

        adapter.saveAll(List.of(order));

        verify(repository).saveAll(anyList());
    }

    @Test
    void findPlannedByCycleAndDate_returnsMappedDomainObjects() {
        // DB와 도메인이 같은 KST 일자를 그대로 사용 — 변환 없음
        OrderEntity entity = new OrderEntity();
        entity.setAccountId(ACCOUNT_ID);
        entity.setStrategyCycleId(STRATEGY_CYCLE_ID);
        entity.setTradeDate(TODAY); // DB도 KST 저장
        entity.setTicker(StrategyTicker.SOXL);
        entity.setOrderType(OrderType.LOC);
        entity.setDirection(OrderDirection.BUY);
        entity.setQuantity(5);
        entity.setPrice(PRICE);
        entity.setStatus(OrderStatus.PLANNED);

        when(repository.findByStrategyCycleIdAndTradeDateAndStatus(
                STRATEGY_CYCLE_ID, TODAY, OrderStatus.PLANNED)) // 변환 없이 그대로 조회
                .thenReturn(List.of(entity));

        List<Order> result = adapter.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY); // KST로 호출

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ticker()).isEqualTo(StrategyTicker.SOXL);
        assertThat(result.getFirst().quantity()).isEqualTo(5);
        assertThat(result.getFirst().status()).isEqualTo(OrderStatus.PLANNED);
        assertThat(result.getFirst().tradeDate()).isEqualTo(TODAY); // toDomain: 변환 없이 그대로 복원
    }

    @Test
    void findPlannedByCycleAndDate_returnsEmptyIfNone() {
        when(repository.findByStrategyCycleIdAndTradeDateAndStatus(any(), any(), any()))
                .thenReturn(List.of());

        List<Order> result = adapter.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void markPlaced_updatesStatusAndExternalOrderId() {
        UUID orderId = UUID.randomUUID();
        OrderEntity entity = new OrderEntity();
        entity.setStatus(OrderStatus.SUBMITTING); // 접수 직전 선점된 상태에서만 PLACED
        when(repository.findById(orderId)).thenReturn(Optional.of(entity));

        adapter.markPlaced(orderId, "ORD-001");

        assertThat(entity.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(entity.getExternalOrderId()).isEqualTo("ORD-001");
        verify(repository).save(entity); // 명시적 save로 dirty checking 없이 처리
    }

    @Test
    void markPlaced_conflictIfDeleted() {
        // 접수 사이 일시중지·삭제 정리가 PLANNED 행을 지움
        UUID orderId = UUID.randomUUID();
        when(repository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.markPlaced(orderId, "ORD-001"))
                .isInstanceOf(OrderStateConflictException.class)
                .hasMessageContaining(orderId.toString());
    }

    @Test
    void markPlaced_conflictIfCancelledMeanwhile() {
        // 접수 사이 사용자가 PLANNED를 취소 — PLACED로 되돌리지 않는다
        UUID orderId = UUID.randomUUID();
        OrderEntity entity = new OrderEntity();
        entity.setStatus(OrderStatus.CANCELLED);
        when(repository.findById(orderId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> adapter.markPlaced(orderId, "ORD-001"))
                .isInstanceOf(OrderStateConflictException.class);
        assertThat(entity.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(repository, never()).save(any());
    }

    @Test
    void markPlaced_idempotentForSameExternalOrderId() {
        // 1차 시도가 커밋된 뒤 예외로 재시도된 경우 — 같은 접수번호면 성공
        UUID orderId = UUID.randomUUID();
        OrderEntity entity = new OrderEntity();
        entity.setStatus(OrderStatus.PLACED);
        entity.setExternalOrderId("ORD-001");
        when(repository.findById(orderId)).thenReturn(Optional.of(entity));

        adapter.markPlaced(orderId, "ORD-001");

        verify(repository, never()).save(any());
    }

    @Test
    void cancelPlanned_cancelsOnlyPlanned() {
        UUID orderId = UUID.randomUUID();
        OrderEntity entity = new OrderEntity();
        entity.setStatus(OrderStatus.PLANNED);
        when(repository.findById(orderId)).thenReturn(Optional.of(entity));

        adapter.cancelPlanned(orderId);

        assertThat(entity.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(repository).save(entity);
    }

    @Test
    void cancelPlanned_conflictIfAlreadyPlaced() {
        // 배치가 먼저 접수 — DB만 CANCELLED로 덮으면 증권사 주문이 고아가 된다
        UUID orderId = UUID.randomUUID();
        OrderEntity entity = new OrderEntity();
        entity.setStatus(OrderStatus.PLACED);
        when(repository.findById(orderId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> adapter.cancelPlanned(orderId))
                .isInstanceOf(OrderStateConflictException.class);
        assertThat(entity.getStatus()).isEqualTo(OrderStatus.PLACED);
    }

    @Test
    void sumFilledBuyAmountByCycleId_returnsSumFromRepository() {
        // FILLED/PARTIALLY_FILLED BUY 합산 — repository 위임 검증
        BigDecimal expected = new BigDecimal("1234.56");
        when(repository.sumFilledBuyAmountByCycleId(STRATEGY_CYCLE_ID)).thenReturn(expected);

        BigDecimal result = adapter.sumFilledBuyAmountByCycleId(STRATEGY_CYCLE_ID);

        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void sumFilledBuyAmountByCycleId_returnsZeroWhenNoFilledOrders() {
        // COALESCE 결과로 repository가 0 반환 — adapter도 ZERO 반환
        when(repository.sumFilledBuyAmountByCycleId(STRATEGY_CYCLE_ID))
                .thenReturn(BigDecimal.ZERO);

        BigDecimal result = adapter.sumFilledBuyAmountByCycleId(STRATEGY_CYCLE_ID);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sumFilledBuyAmountByCycleId_returnsZeroWhenRepositoryReturnsNull() {
        // null 방어 — COALESCE가 있어도 BigDecimal null 가능성 대비
        when(repository.sumFilledBuyAmountByCycleId(STRATEGY_CYCLE_ID)).thenReturn(null);

        BigDecimal result = adapter.sumFilledBuyAmountByCycleId(STRATEGY_CYCLE_ID);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker_delegatesWithTradeDate() {
        // 변환 없이 KST 거래일 그대로 PLANNED/PLACED SELL 예약 수량을 합산한다
        when(repository.sumPlannedOrPlacedSellQuantityByAccountIdAndTradeDateAndTicker(
                ACCOUNT_ID, TODAY, StrategyTicker.SOXL.name())).thenReturn(8L);

        int result = adapter.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                ACCOUNT_ID, TODAY, StrategyTicker.SOXL);

        assertThat(result).isEqualTo(8);
    }
}

