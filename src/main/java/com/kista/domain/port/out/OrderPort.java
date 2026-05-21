package com.kista.domain.port.out;

import com.kista.domain.model.order.Order;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface OrderPort {
    // 계획 주문 일괄 저장 (신규 PLANNED 상태)
    void saveAll(List<Order> orders);

    // 특정 계좌·날짜의 PLANNED 주문 조회 (waitForOrderTime 이후 실행 단계에서 호출)
    List<Order> findPlannedByAccountAndDate(UUID accountId, LocalDate tradeDate);

    // kisOrderPort.place() 완료 후 PLACED 상태 + kisOrderId 기록
    void markPlaced(UUID orderId, String kisOrderId);
}
