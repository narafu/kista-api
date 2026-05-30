package com.kista.domain.port.out;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TradingCycleHistoryPort {
    TradingCycleHistory save(TradingCycleHistory history);

    List<TradingCycleHistory> findRecentByCycleId(UUID cycleId, int limit);

    // 계좌 ID 기준 이력 조회 (ticker 포함, 날짜 범위 필터)
    List<AccountCycleHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to);
}
