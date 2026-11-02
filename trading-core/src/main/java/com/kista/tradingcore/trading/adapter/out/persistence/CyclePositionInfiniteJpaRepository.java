package com.kista.tradingcore.trading.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface CyclePositionInfiniteJpaRepository extends JpaRepository<CyclePositionInfiniteEntity, UUID> {

    @Query(value = """
            SELECT cpi.* FROM cycle_position_infinite cpi
            JOIN cycle_position cp ON cpi.cycle_position_id = cp.id
            WHERE cp.strategy_cycle_id = :cycleId
              AND cp.deleted_at IS NULL
              AND cpi.deleted_at IS NULL
            ORDER BY cp.created_at DESC, cp.id DESC
            """, nativeQuery = true)
    List<CyclePositionInfiniteEntity> findByStrategyCycleIdOrderByCreatedAtDesc(
            @Param("cycleId") UUID cycleId,
            Pageable pageable);

    // 사이클별 최신 상세 1건 — 위 쿼리와 같은 필터·정렬 (id DESC는 동시각 타이브레이커)
    @Query(value = """
            SELECT DISTINCT ON (cp.strategy_cycle_id)
                   cp.strategy_cycle_id AS cycleId, cpi.cycle_position_id AS cyclePositionId, cpi.is_reverse_mode AS reverseMode
            FROM cycle_position_infinite cpi
            JOIN cycle_position cp ON cpi.cycle_position_id = cp.id
            WHERE cp.strategy_cycle_id IN (:cycleIds)
              AND cp.deleted_at IS NULL
              AND cpi.deleted_at IS NULL
            ORDER BY cp.strategy_cycle_id, cp.created_at DESC, cp.id DESC
            """, nativeQuery = true)
    List<LatestRow> findLatestByStrategyCycleIdIn(@Param("cycleIds") Collection<UUID> cycleIds);

    // 최신 상세 projection (native 컬럼 별칭과 getter 매핑)
    interface LatestRow {
        UUID getCycleId();
        UUID getCyclePositionId();
        boolean getReverseMode();
    }
}
