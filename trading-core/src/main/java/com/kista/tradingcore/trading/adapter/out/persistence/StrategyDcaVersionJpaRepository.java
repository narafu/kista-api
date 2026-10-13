package com.kista.tradingcore.trading.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface StrategyDcaVersionJpaRepository extends JpaRepository<StrategyDcaVersionEntity, UUID> {

    // 활성(삭제되지 않은) 최신 버전의 DCA 설정 — strategy_vr_version 조회와 같은 조인
    @Query(value = """
            SELECT sdv.* FROM strategy_dca_version sdv
            JOIN strategy_version sv ON sv.id = sdv.strategy_version_id AND sv.deleted_at IS NULL
            WHERE sv.strategy_id = :strategyId
            ORDER BY sdv.created_at DESC LIMIT 1
            """, nativeQuery = true)
    Optional<StrategyDcaVersionEntity> findActiveByStrategyId(@Param("strategyId") UUID strategyId);

    // 여러 전략의 활성 버전 설정 + strategy_id (배치 조회)
    @Query(value = """
            SELECT DISTINCT ON (sv.strategy_id) sv.strategy_id AS strategyId, sdv.strategy_version_id AS strategyVersionId
            FROM strategy_dca_version sdv
            JOIN strategy_version sv ON sv.id = sdv.strategy_version_id AND sv.deleted_at IS NULL
            WHERE sv.strategy_id IN (:strategyIds)
            ORDER BY sv.strategy_id, sdv.created_at DESC
            """, nativeQuery = true)
    List<StrategyVersionLink> findActiveLinks(@Param("strategyIds") Collection<UUID> strategyIds);

    // native projection — strategyId ↔ 활성 versionId
    interface StrategyVersionLink {
        UUID getStrategyId();
        UUID getStrategyVersionId();
    }
}
