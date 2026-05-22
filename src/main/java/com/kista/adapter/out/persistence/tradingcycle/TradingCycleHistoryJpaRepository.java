package com.kista.adapter.out.persistence.tradingcycle;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface TradingCycleHistoryJpaRepository extends JpaRepository<TradingCycleHistoryEntity, UUID> {

    Optional<TradingCycleHistoryEntity> findByTradingCycleIdAndTradeDate(UUID tradingCycleId, LocalDate tradeDate);

    List<TradingCycleHistoryEntity> findTop10ByTradingCycleIdOrderByTradeDateDesc(UUID tradingCycleId);
}
