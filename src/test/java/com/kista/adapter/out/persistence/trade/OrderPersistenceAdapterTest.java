package com.kista.adapter.out.persistence.trade;

import com.kista.common.TradeDateConverter;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderPersistenceAdapterTest {

    @Mock OrderJpaRepository repository;
    OrderPersistenceAdapter adapter;

    static final UUID ACCOUNT_ID = UUID.randomUUID();
    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();
    static final LocalDate TODAY = LocalDate.now();
    static final BigDecimal PRICE = new BigDecimal("22.0000");

    @BeforeEach
    void setUp() {
        adapter = new OrderPersistenceAdapter(repository);
    }

    @Test
    void saveAll_delegatesToRepository() {
        Order order = new Order(null, ACCOUNT_ID, STRATEGY_CYCLE_ID, TODAY, Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 5, PRICE,
                Order.OrderStatus.PLANNED, null, null, null);

        adapter.saveAll(List.of(order));

        verify(repository).saveAll(anyList());
    }

    @Test
    void findPlannedByCycleAndDate_returnsMappedDomainObjects() {
        // DB는 UTC 저장 — adapter가 KST TODAY를 UTC(TODAY-1)로 변환해서 조회
        LocalDate utcDate = TradeDateConverter.toUtc(TODAY);
        OrderEntity entity = new OrderEntity();
        entity.setAccountId(ACCOUNT_ID);
        entity.setStrategyCycleId(STRATEGY_CYCLE_ID);
        entity.setTradeDate(utcDate); // DB에는 UTC 저장
        entity.setTicker(Ticker.SOXL);
        entity.setOrderType(Order.OrderType.LOC);
        entity.setDirection(Order.OrderDirection.BUY);
        entity.setQuantity(5);
        entity.setPrice(PRICE);
        entity.setStatus(Order.OrderStatus.PLANNED);

        when(repository.findByStrategyCycleIdAndTradeDateAndStatus(
                STRATEGY_CYCLE_ID, utcDate, Order.OrderStatus.PLANNED)) // UTC 변환 후 조회
                .thenReturn(List.of(entity));

        List<Order> result = adapter.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY); // KST로 호출

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ticker()).isEqualTo(Ticker.SOXL);
        assertThat(result.getFirst().quantity()).isEqualTo(5);
        assertThat(result.getFirst().status()).isEqualTo(Order.OrderStatus.PLANNED);
        assertThat(result.getFirst().tradeDate()).isEqualTo(TODAY); // toDomain: UTC → KST 복원
    }

    @Test
    void findPlannedByCycleAndDate_returnsEmptyIfNone() {
        when(repository.findByStrategyCycleIdAndTradeDateAndStatus(any(), any(), any()))
                .thenReturn(List.of());

        List<Order> result = adapter.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void markPlaced_updatesStatusAndKisOrderId() {
        UUID orderId = UUID.randomUUID();
        OrderEntity entity = new OrderEntity();
        entity.setStatus(Order.OrderStatus.PLANNED);
        when(repository.findById(orderId)).thenReturn(Optional.of(entity));

        adapter.markPlaced(orderId, "ORD-001");

        assertThat(entity.getStatus()).isEqualTo(Order.OrderStatus.PLACED);
        assertThat(entity.getKisOrderId()).isEqualTo("ORD-001");
        verify(repository).save(entity); // 명시적 save로 dirty checking 없이 처리
    }

    @Test
    void markPlaced_throwsIfNotFound() {
        UUID orderId = UUID.randomUUID();
        when(repository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.markPlaced(orderId, "ORD-001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(orderId.toString());
    }
}
