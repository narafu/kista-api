package com.kista.adapter.out.persistence.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface StrategyCycleJpaRepository extends JpaRepository<StrategyCycleEntity, UUID> {

    // 전략의 현재 사이클 — deleted_at IS NULL(@SQLRestriction) 중 createdAt 최신 1건
    Optional<StrategyCycleEntity> findTop1ByStrategyIdOrderByCreatedAtDesc(UUID strategyId);

    @Modifying
    @Query("UPDATE StrategyCycleEntity sc SET sc.deletedAt = :now WHERE sc.strategyId = :strategyId AND sc.deletedAt IS NULL")
    void softDeleteByStrategyId(@Param("strategyId") UUID strategyId, @Param("now") Instant now);

    // 계좌에 속한 모든 전략의 사이클 소프트 삭제
    @Modifying
    @Query(value = """
            UPDATE strategy_cycle sc SET deleted_at = :now
            FROM strategy s WHERE sc.strategy_id = s.id
            AND s.account_id = :accountId AND sc.deleted_at IS NULL
            """, nativeQuery = true)
    void softDeleteByAccountId(@Param("accountId") UUID accountId, @Param("now") Instant now);

    // 사용자 소유 모든 사이클 소프트 삭제
    @Modifying
    @Query(value = """
            UPDATE strategy_cycle sc SET deleted_at = :now
            FROM strategy s JOIN accounts a ON s.account_id = a.id
            WHERE sc.strategy_id = s.id
            AND a.user_id = :userId AND sc.deleted_at IS NULL
            """, nativeQuery = true)
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
