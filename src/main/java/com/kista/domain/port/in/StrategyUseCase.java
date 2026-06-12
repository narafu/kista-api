package com.kista.domain.port.in;

import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.StrategyDetail;
import com.kista.domain.model.strategy.UpdateStrategyCommand;

import java.util.List;
import java.util.UUID;

public interface StrategyUseCase {
    // --- 조회 ---
    List<StrategyDetail> listByAccountId(UUID accountId, UUID requesterId);
    StrategyDetail getById(UUID strategyId, UUID requesterId);
    // 로그인 사용자의 전 계좌 전략 목록 (계좌 무관 집계) — 모바일 전략 탭용
    List<StrategyDetail> listByUserId(UUID userId);

    // --- 등록 ---
    StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand command);

    // --- 수정 ---
    StrategyDetail update(UUID strategyId, UUID requesterId, UpdateStrategyCommand cmd);

    // --- 삭제 ---
    void delete(UUID strategyId, UUID requesterId);

    // --- 일시정지 / 재개 ---
    void pause(UUID strategyId, UUID requesterId);
    void resume(UUID strategyId, UUID requesterId);
}
