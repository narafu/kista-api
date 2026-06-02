package com.kista.domain.port.in;

import java.util.UUID;

public interface ManualExecuteTradingUseCase {
    // INFINITE 사이클 수동 실행 — 동기: 소유권·타입·상태·중복 검증, 비동기: LOC 주문 접수
    // 예외: SecurityException(403), IllegalArgumentException(400), IllegalStateException(409), NoSuchElementException(404)
    void execute(UUID cycleId, UUID requesterId);
}
