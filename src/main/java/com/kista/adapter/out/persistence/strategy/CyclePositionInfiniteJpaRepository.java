package com.kista.adapter.out.persistence.strategy;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface CyclePositionInfiniteJpaRepository extends JpaRepository<CyclePositionInfiniteEntity, UUID> {

    @Query(value = """
            SELECT cpi.* FROM cycle_position_infinite cpi
            JOIN cycle_position cp ON cpi.cycle_position_id = cp.id
            WHERE cp.strategy_cycle_id = :cycleId
              AND cp.deleted_at IS NULL
              AND cpi.deleted_at IS NULL
            ORDER BY cp.created_at DESC
            """, nativeQuery = true)
    List<CyclePositionInfiniteEntity> findTopNByStrategyCycleIdOrderByCreatedAtDesc(
            @Param("cycleId") UUID cycleId,
            Pageable pageable);

    @Modifying
    @Query(value = """
            DELETE FROM cycle_position_infinite cpi
            USING cycle_position cp, strategy_cycle sc
            WHERE cpi.cycle_position_id = cp.id
              AND cp.strategy_cycle_id = sc.id
              AND sc.strategy_id = :strategyId
            """, nativeQuery = true)
    void deleteByStrategyId(@Param("strategyId") UUID strategyId);
}
