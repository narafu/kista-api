package com.kista.adapter.out.persistence;

import com.kista.domain.model.PlannedOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface PlannedOrderJpaRepository extends JpaRepository<PlannedOrderEntity, UUID> {
    // account_id + trade_date + status = PENDING 조건으로 실행 대상 조회
    List<PlannedOrderEntity> findByAccountIdAndTradeDateAndStatus(
            UUID accountId, LocalDate tradeDate, PlannedOrder.PlannedOrderStatus status);
}
