package com.kista.adapter.out.persistence.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.UUID;

interface UsMarketHolidayJpaRepository extends JpaRepository<UsMarketHolidayEntity, UUID> {

    boolean existsByTradeDate(LocalDate tradeDate);

    @Query("SELECT COUNT(e) FROM UsMarketHolidayEntity e WHERE YEAR(e.tradeDate) = :year")
    long countByYear(@Param("year") int year);

    @Modifying
    @Query("DELETE FROM UsMarketHolidayEntity e WHERE YEAR(e.tradeDate) = :year")
    void deleteByYear(@Param("year") int year);
}
