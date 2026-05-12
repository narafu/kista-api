package com.kista.domain.port.out;

import com.kista.domain.model.PlannedOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PlannedOrderPort {
    // 계획 주문 일괄 저장 (신규 PENDING 상태)
    void saveAll(List<PlannedOrder> orders);

    // 특정 계좌·날짜의 PENDING 주문 조회 (waitForOrderTime 이후 실행 단계에서 호출)
    List<PlannedOrder> findPendingByAccountAndDate(UUID accountId, LocalDate tradeDate);

    // kisOrderPort.place() 완료 후 EXECUTED 상태 + kisOrderId 기록
    void markExecuted(UUID plannedOrderId, String kisOrderId);
}
