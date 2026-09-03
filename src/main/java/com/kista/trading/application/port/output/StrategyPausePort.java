package com.kista.trading.application.port.output;

import java.util.UUID;

// 시스템 자동 일시정지 전용(CycleRotationService — 사이클 재등록 실패 시) — StrategyUseCase.pause()의
// 소유권검증 경로와 무관한 별도 포트. 읽기(StrategyLookupPort)와 쓰기를 섞지 않는다(ISP).
public interface StrategyPausePort {
    void pause(UUID strategyId);
}
