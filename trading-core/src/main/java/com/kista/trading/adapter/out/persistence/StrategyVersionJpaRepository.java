package com.kista.trading.adapter.out.persistence;

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

    @Query("""
            SELECT COALESCE(MAX(sv.versionNo), 0)
            FROM StrategyVersionEntity sv
            WHERE sv.strategyId = :strategyId
            """)
    int findMaxVersionNoByStrategyId(@Param("strategyId") UUID strategyId);

    @Modifying
    @Query("UPDATE StrategyVersionEntity sv SET sv.deletedAt = :now WHERE sv.strategyId = :strategyId AND sv.deletedAt IS NULL")
    void softDeleteActiveByStrategyId(@Param("strategyId") UUID strategyId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE StrategyVersionEntity sv SET sv.deletedAt = :now WHERE sv.strategyId = :strategyId AND sv.deletedAt IS NULL")
    void softDeleteByStrategyId(@Param("strategyId") UUID strategyId, @Param("now") Instant now);
}
