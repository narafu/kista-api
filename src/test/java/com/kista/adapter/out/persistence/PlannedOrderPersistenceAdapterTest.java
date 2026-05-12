package com.kista.adapter.out.persistence;

import com.kista.domain.model.Order;
import com.kista.domain.model.PlannedOrder;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlannedOrderPersistenceAdapterTest {

    @Mock PlannedOrderJpaRepository repository;
    PlannedOrderPersistenceAdapter adapter;

    static final UUID ACCOUNT_ID = UUID.randomUUID();
    static final LocalDate TODAY = LocalDate.now();
    static final BigDecimal PRICE = new BigDecimal("22.0000");

    @BeforeEach
    void setUp() {
        adapter = new PlannedOrderPersistenceAdapter(repository);
    }

    @Test
    void saveAll_delegatesToRepository() {
        PlannedOrder order = new PlannedOrder(null, ACCOUNT_ID, TODAY, "SOXL",
                Order.OrderType.LOC, Order.OrderDirection.BUY, 5, PRICE,
                PlannedOrder.PlannedOrderStatus.PENDING, null);

        adapter.saveAll(List.of(order));

        verify(repository).saveAll(anyList());
    }

    @Test
    void findPendingByAccountAndDate_returnsMappedDomainObjects() {
        PlannedOrderEntity entity = new PlannedOrderEntity();
        entity.setAccountId(ACCOUNT_ID);
        entity.setTradeDate(TODAY);
        entity.setSymbol("SOXL");
        entity.setOrderType(Order.OrderType.LOC);
        entity.setDirection(Order.OrderDirection.BUY);
        entity.setQty(5);
        entity.setPrice(PRICE);
        entity.setStatus(PlannedOrder.PlannedOrderStatus.PENDING);

        when(repository.findByAccountIdAndTradeDateAndStatus(
                ACCOUNT_ID, TODAY, PlannedOrder.PlannedOrderStatus.PENDING))
                .thenReturn(List.of(entity));

        List<PlannedOrder> result = adapter.findPendingByAccountAndDate(ACCOUNT_ID, TODAY);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("SOXL");
        assertThat(result.get(0).qty()).isEqualTo(5);
        assertThat(result.get(0).status()).isEqualTo(PlannedOrder.PlannedOrderStatus.PENDING);
    }

    @Test
    void findPendingByAccountAndDate_returnsEmptyIfNone() {
        when(repository.findByAccountIdAndTradeDateAndStatus(any(), any(), any()))
                .thenReturn(List.of());

        List<PlannedOrder> result = adapter.findPendingByAccountAndDate(ACCOUNT_ID, TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void markExecuted_updatesStatusAndKisOrderId() {
        UUID plannedId = UUID.randomUUID();
        PlannedOrderEntity entity = new PlannedOrderEntity();
        entity.setStatus(PlannedOrder.PlannedOrderStatus.PENDING);
        when(repository.findById(plannedId)).thenReturn(Optional.of(entity));

        adapter.markExecuted(plannedId, "ORD-001");

        assertThat(entity.getStatus()).isEqualTo(PlannedOrder.PlannedOrderStatus.EXECUTED);
        assertThat(entity.getKisOrderId()).isEqualTo("ORD-001");
        verify(repository).save(entity); // 명시적 save로 dirty checking 없이 처리
    }

    @Test
    void markExecuted_doesNothingIfNotFound() {
        UUID plannedId = UUID.randomUUID();
        when(repository.findById(plannedId)).thenReturn(Optional.empty());

        adapter.markExecuted(plannedId, "ORD-001");

        verify(repository, never()).save(any());
    }
}
