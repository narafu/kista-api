package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.order.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {
    // account_id + trade_date + status = PLANNED 조건으로 실행 대상 조회
    List<OrderEntity> findByAccountIdAndTradeDateAndStatus(
            UUID accountId, LocalDate tradeDate, Order.OrderStatus status);
}
