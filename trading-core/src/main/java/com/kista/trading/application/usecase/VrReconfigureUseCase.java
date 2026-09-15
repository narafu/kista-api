package com.kista.trading.application.usecase;

import com.kista.trading.domain.model.ReconfigureVrCommand;

import java.util.UUID;

public interface VrReconfigureUseCase {
    // VR 전략 운영 중 재설정 — 새 버전 발급 + 강제 롤오버(현재 사이클 종료→새 사이클 즉시 생성)
    void reconfigure(UUID strategyId, UUID requesterId, ReconfigureVrCommand cmd);
}
