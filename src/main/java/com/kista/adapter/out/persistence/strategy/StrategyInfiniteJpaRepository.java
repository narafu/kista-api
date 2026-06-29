package com.kista.adapter.out.persistence.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface StrategyInfiniteJpaRepository extends JpaRepository<StrategyInfiniteEntity, UUID> {

    @Query(value = """
            SELECT siv.* FROM strategy_infinite_version siv
            JOIN strategy_version sv ON siv.strategy_version_id = sv.id
            WHERE sv.strategy_id = :strategyId
              AND sv.deleted_at IS NULL
              AND siv.deleted_at IS NULL
            LIMIT 1
            """, nativeQuery = true)
    Optional<StrategyInfiniteEntity> findActiveByStrategyId(@Param("strategyId") UUID strategyId);

    @Modifying
    @Query(value = """
            UPDATE strategy_infinite_version siv
            SET deleted_at = :now
            FROM strategy_version sv
            WHERE siv.strategy_version_id = sv.id
              AND sv.strategy_id = :strategyId
              AND siv.deleted_at IS NULL
            """, nativeQuery = true)
    void softDeleteByStrategyId(@Param("strategyId") UUID strategyId, @Param("now") Instant now);
}
