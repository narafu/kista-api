package com.kista.domain.port.out;

import com.kista.domain.model.tradingcycle.TradingCycleHistory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradingCycleHistoryRepository {
    // 일별 스냅샷 저장 (UNIQUE(trading_cycle_id, trade_date) — 중복 시 호출 측에서 처리)
    TradingCycleHistory save(TradingCycleHistory history);

    Optional<TradingCycleHistory> findByCycleIdAndDate(UUID cycleId, LocalDate date);

    List<TradingCycleHistory> findRecentByCycleId(UUID cycleId, int limit);
}
