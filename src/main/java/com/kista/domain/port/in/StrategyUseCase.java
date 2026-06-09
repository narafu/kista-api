package com.kista.domain.port.in;

import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.UpdateStrategyCommand;

import java.util.List;
import java.util.UUID;

public interface StrategyUseCase {
    // --- 조회 ---
    List<Strategy> listByAccountId(UUID accountId, UUID requesterId);
    Strategy getById(UUID strategyId, UUID requesterId);

    // --- 등록 ---
    Strategy register(UUID userId, UUID accountId, RegisterStrategyCommand command);

    // --- 수정 ---
    Strategy update(UUID strategyId, UUID requesterId, UpdateStrategyCommand cmd);

    // --- 삭제 ---
    void delete(UUID strategyId, UUID requesterId);

    // --- 일시정지 / 재개 ---
    void pause(UUID strategyId, UUID requesterId);
    void resume(UUID strategyId, UUID requesterId);
}
