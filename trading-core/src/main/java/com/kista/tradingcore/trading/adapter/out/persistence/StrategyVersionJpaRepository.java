package com.kista.tradingcore.trading.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface StrategyVersionJpaRepository extends JpaRepository<StrategyVersionEntity, UUID> {

    Optional<StrategyVersionEntity> findTop1ByStrategyIdAndDeletedAtIsNullOrderByVersionNoDesc(UUID strategyId);

    // 여러 전략의 활성 버전 배치 조회 (목록 조회 N+1 방지) — strategy_id별 version_no 최대 1건 (id DESC tie-break)
    @Query(value = """
            SELECT DISTINCT ON (strategy_id) *
            FROM strategy_version
            WHERE strategy_id IN (:strategyIds) AND deleted_at IS NULL
            ORDER BY strategy_id, version_no DESC, created_at DESC, id DESC
            """, nativeQuery = true)
    List<StrategyVersionEntity> findActiveByStrategyIdIn(@Param("strategyIds") Collection<UUID> strategyIds);

    // native — @SQLRestriction을 우회해 삭제 버전 포함 MAX (uq_strategy_version_strategy_version_no는 삭제 행도 포함)
    @Query(value = "SELECT COALESCE(MAX(version_no), 0) FROM strategy_version WHERE strategy_id = :strategyId", nativeQuery = true)
    int findMaxVersionNoByStrategyId(@Param("strategyId") UUID strategyId);

    @Modifying
    @Query("UPDATE StrategyVersionEntity sv SET sv.deletedAt = :now WHERE sv.strategyId = :strategyId AND sv.deletedAt IS NULL")
    void softDeleteActiveByStrategyId(@Param("strategyId") UUID strategyId, @Param("now") Instant now);
}
