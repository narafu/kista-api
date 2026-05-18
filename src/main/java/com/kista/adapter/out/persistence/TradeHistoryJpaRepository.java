package com.kista.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface TradeHistoryJpaRepository extends JpaRepository<TradeHistoryEntity, UUID> {
    List<TradeHistoryEntity> findByTradeDateBetweenAndSymbol(LocalDate from, LocalDate to, String symbol);

    // symbol 필터 없이 기간 내 전체 거래 조회 (관리자용)
    List<TradeHistoryEntity> findByTradeDateBetween(LocalDate from, LocalDate to);
}
