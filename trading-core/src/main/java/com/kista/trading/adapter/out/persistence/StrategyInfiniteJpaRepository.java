package com.kista.trading.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface StrategyInfiniteJpaRepository extends JpaRepository<StrategyInfiniteEntity, UUID> {

    @Query(value = """
            SELECT siv.* FROM strategy_infinite_version siv
            JOIN strategy_version sv ON siv.strategy_version_id = sv.id
            WHERE sv.strategy_id = :strategyId
              AND sv.deleted_at IS NULL
              AND siv.deleted_at IS NULL
            ORDER BY siv.created_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<StrategyInfiniteEntity> findActiveByStrategyId(@Param("strategyId") UUID strategyId);
}
