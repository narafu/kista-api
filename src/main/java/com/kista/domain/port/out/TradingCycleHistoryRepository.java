package com.kista.domain.port.out;

import com.kista.domain.model.tradingcycle.TradingCycleHistory;

import java.util.List;
import java.util.UUID;

public interface TradingCycleHistoryRepository {
    TradingCycleHistory save(TradingCycleHistory history);

    List<TradingCycleHistory> findRecentByCycleId(UUID cycleId, int limit);
}
