package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface TradeHistoryJpaRepository extends JpaRepository<TradeHistoryEntity, UUID> {
    List<TradeHistoryEntity> findByTradeDateBetweenAndTicker(LocalDate from, LocalDate to, Ticker ticker);

    // ticker 필터 없이 기간 내 전체 거래 조회 (관리자용)
    List<TradeHistoryEntity> findByTradeDateBetween(LocalDate from, LocalDate to);
}
