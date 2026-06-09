package com.kista.domain.port.in;

import com.kista.domain.model.order.CancelResult;

import java.util.UUID;

public interface CancelOrderUseCase {

    // 오늘 수동 실행으로 PLACED된 사이클 주문 전체 취소 (best-effort)
    // 예외: SecurityException(403), NoSuchElementException(404)
    CancelResult cancelByCycle(UUID cycleId, UUID requesterId);

    // 특정 주문 1건 취소
    // 예외: SecurityException(403), NoSuchElementException(404), IllegalStateException(PLACED 아닌 경우→409)
    void cancelOrder(UUID orderId, UUID requesterId);
}
