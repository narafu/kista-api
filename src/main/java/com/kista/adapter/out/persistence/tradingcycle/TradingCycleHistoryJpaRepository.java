package com.kista.adapter.out.persistence.tradingcycle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface TradingCycleHistoryJpaRepository extends JpaRepository<TradingCycleHistoryEntity, UUID> {

    List<TradingCycleHistoryEntity> findTop10ByTradingCycleIdOrderByCreatedAtDesc(UUID tradingCycleId);

    // 계좌 ID 기준 날짜 범위 조회 — TradingCycleEntity의 @SQLRestriction(deleted_at IS NULL)이 서브쿼리에도 적용됨
    @Query("SELECT tch FROM TradingCycleHistoryEntity tch " +
           "WHERE tch.tradingCycleId IN " +
           "(SELECT tc.id FROM TradingCycleEntity tc WHERE tc.accountId = :accountId) " +
           "AND tch.createdAt >= :from AND tch.createdAt < :to " +
           "ORDER BY tch.createdAt DESC")
    List<TradingCycleHistoryEntity> findByAccountIdAndDateRange(
            @Param("accountId") UUID accountId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
