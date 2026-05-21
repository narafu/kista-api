package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.order.Order;
import com.kista.domain.port.out.OrderPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // OrderJpaRepository가 package-private
public class OrderPersistenceAdapter implements OrderPort {

    private final OrderJpaRepository repository;

    @Override
    public void saveAll(List<Order> orders) {
        // Order → Entity 변환 후 일괄 저장 (id=null → Hibernate UUID 자동 생성)
        repository.saveAll(orders.stream().map(this::toEntity).toList());
    }

    @Override
    public List<Order> findPlannedByAccountAndDate(UUID accountId, LocalDate tradeDate) {
        // PLANNED 상태인 오늘 계획 주문만 조회
        return repository
                .findByAccountIdAndTradeDateAndStatus(
                        accountId, tradeDate, Order.OrderStatus.PLANNED)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void markPlaced(UUID orderId, String kisOrderId) {
        // 명시적 save로 dirty checking 의존 없이 PLACED + kisOrderId 기록
        OrderEntity e = repository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        e.setStatus(Order.OrderStatus.PLACED);
        e.setKisOrderId(kisOrderId);
        repository.save(e);
    }

    private OrderEntity toEntity(Order o) {
        OrderEntity e = new OrderEntity();
        e.setAccountId(o.accountId());
        e.setTradeDate(o.tradeDate());
        e.setTicker(o.ticker());
        e.setOrderType(o.orderType());
        e.setDirection(o.direction());
        e.setQuantity(o.quantity());
        e.setPrice(o.price());
        e.setStatus(o.status());
        e.setKisOrderId(o.kisOrderId());
        return e;
    }

    private Order toDomain(OrderEntity e) {
        return new Order(
                e.getId(), e.getAccountId(), e.getTradeDate(), e.getTicker(),
                e.getOrderType(), e.getDirection(), e.getQuantity(), e.getPrice(),
                e.getStatus(), e.getKisOrderId()
        );
    }
}
