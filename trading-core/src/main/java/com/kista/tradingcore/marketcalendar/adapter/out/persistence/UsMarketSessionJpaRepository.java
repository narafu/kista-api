package com.kista.tradingcore.marketcalendar.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

interface UsMarketSessionJpaRepository extends JpaRepository<UsMarketSessionEntity, LocalDate> {
    List<UsMarketSessionEntity> findByTradeDateBetween(LocalDate start, LocalDate end);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UsMarketSessionEntity e WHERE e.tradeDate BETWEEN :start AND :end")
    void deleteByTradeDateBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
}