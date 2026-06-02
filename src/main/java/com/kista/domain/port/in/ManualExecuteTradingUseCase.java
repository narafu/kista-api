package com.kista.domain.port.in;

import com.kista.domain.model.order.Order;

import java.util.List;
import java.util.UUID;

public interface ManualExecuteTradingUseCase {
    // INFINITE 사이클 수동 실행 — 동기: 소유권·타입·상태·중복 검증 + Phase A/B(주문 접수), 비동기: Phase C(체결·이력·알림)
    // 반환: 접수된 주문 목록 (휴장·잔고부족 시 빈 리스트)
    // 예외: SecurityException(403), IllegalArgumentException(400), IllegalStateException(409), NoSuchElementException(404)
    List<Order> execute(UUID cycleId, UUID requesterId);
}
