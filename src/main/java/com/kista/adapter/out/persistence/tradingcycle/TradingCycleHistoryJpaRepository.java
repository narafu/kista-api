package com.kista.adapter.out.persistence.tradingcycle;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface TradingCycleHistoryJpaRepository extends JpaRepository<TradingCycleHistoryEntity, UUID> {

    List<TradingCycleHistoryEntity> findTop10ByTradingCycleIdOrderByCreatedAtDesc(UUID tradingCycleId);
}
