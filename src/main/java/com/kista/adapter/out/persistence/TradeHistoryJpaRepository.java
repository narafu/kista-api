package com.kista.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface TradeHistoryJpaRepository extends JpaRepository<TradeHistoryEntity, UUID> {
    List<TradeHistoryEntity> findByTradeDateBetweenAndSymbol(LocalDate from, LocalDate to, String symbol);
}
