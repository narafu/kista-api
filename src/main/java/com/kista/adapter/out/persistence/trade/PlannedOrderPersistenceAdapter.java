package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.order.PlannedOrder;
import com.kista.domain.port.out.PlannedOrderPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // PlannedOrderJpaRepository가 package-private
public class PlannedOrderPersistenceAdapter implements PlannedOrderPort {

    private final PlannedOrderJpaRepository repository;

    @Override
    public void saveAll(List<PlannedOrder> orders) {
        // PlannedOrder → Entity 변환 후 일괄 저장 (id=null → Hibernate UUID 자동 생성)
        repository.saveAll(orders.stream().map(this::toEntity).toList());
    }

    @Override
    public List<PlannedOrder> findPendingByAccountAndDate(UUID accountId, LocalDate tradeDate) {
        // PENDING 상태인 오늘 계획 주문만 조회
        return repository
                .findByAccountIdAndTradeDateAndStatus(
                        accountId, tradeDate, PlannedOrder.PlannedOrderStatus.PENDING)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void markExecuted(UUID plannedOrderId, String orderId) {
        // 명시적 save로 dirty checking 의존 없이 EXECUTED + orderId 기록
        repository.findById(plannedOrderId).ifPresent(e -> {
            e.setStatus(PlannedOrder.PlannedOrderStatus.EXECUTED);
            e.setOrderId(orderId);
            repository.save(e);
        });
    }

    private PlannedOrderEntity toEntity(PlannedOrder p) {
        PlannedOrderEntity e = new PlannedOrderEntity();
        e.setAccountId(p.accountId());
        e.setTradeDate(p.tradeDate());
        e.setTicker(p.ticker());
        e.setOrderType(p.orderType());
        e.setDirection(p.direction());
        e.setQuantity(p.quantity());
        e.setPrice(p.price());
        e.setStatus(p.status());
        e.setOrderId(p.orderId());
        return e;
    }

    private PlannedOrder toDomain(PlannedOrderEntity e) {
        return new PlannedOrder(
                e.getId(), e.getAccountId(), e.getTradeDate(), e.getTicker(),
                e.getOrderType(), e.getDirection(), e.getQuantity(), e.getPrice(),
                e.getStatus(), e.getOrderId()
        );
    }
}
