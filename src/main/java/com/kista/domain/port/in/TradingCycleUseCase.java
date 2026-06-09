package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.RegisterCycleCommand;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.UpdateCycleCommand;

import java.util.List;
import java.util.UUID;

public interface TradingCycleUseCase {
    // --- 조회 ---
    List<TradingCycle> listByAccountId(UUID accountId, UUID requesterId);
    TradingCycle getById(UUID cycleId, UUID requesterId);

    // --- 등록 ---
    TradingCycle register(UUID userId, UUID accountId, RegisterCycleCommand command);

    // --- 수정 ---
    TradingCycle update(UUID cycleId, UUID requesterId, UpdateCycleCommand cmd);

    // --- 삭제 ---
    void delete(UUID cycleId, UUID requesterId);

    // --- 일시정지 / 재개 ---
    void pause(UUID cycleId, UUID requesterId);
    void resume(UUID cycleId, UUID requesterId);
}
