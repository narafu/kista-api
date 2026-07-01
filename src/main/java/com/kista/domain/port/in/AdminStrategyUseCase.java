package com.kista.domain.port.in;

import java.util.UUID;

// 관리자 전략 상태 변경 — 대상 전략을 직접 pause/resume
public interface AdminStrategyUseCase {
    void pauseStrategy(UUID adminId, UUID accountId, UUID strategyId);
    void resumeStrategy(UUID adminId, UUID accountId, UUID strategyId);
}
