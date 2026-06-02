package com.kista.adapter.out.persistence.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface UsMarketHolidayJpaRepository extends JpaRepository<UsMarketHolidayEntity, UUID> {

    boolean existsByTradeDate(LocalDate tradeDate);

    // 특정 기간의 휴장일 목록 조회 (월별 필터링에 사용)
    List<UsMarketHolidayEntity> findByTradeDateBetween(LocalDate start, LocalDate end);

    @Query("SELECT COUNT(e) FROM UsMarketHolidayEntity e WHERE YEAR(e.tradeDate) = :year")
    long countByYear(@Param("year") int year);

    @Modifying
    @Query("DELETE FROM UsMarketHolidayEntity e WHERE YEAR(e.tradeDate) = :year")
    void deleteByYear(@Param("year") int year);

    @Modifying
    @Query("DELETE FROM UsMarketHolidayEntity e WHERE YEAR(e.tradeDate) = :year AND MONTH(e.tradeDate) = :month")
    void deleteByMonth(@Param("year") int year, @Param("month") int month);
}
