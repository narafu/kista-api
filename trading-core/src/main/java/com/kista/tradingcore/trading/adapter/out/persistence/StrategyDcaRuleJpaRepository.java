package com.kista.tradingcore.trading.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface StrategyDcaRuleJpaRepository extends JpaRepository<StrategyDcaRuleEntity, UUID> {

    // 여러 버전 규칙 일괄 조회 — 입력 순서
    List<StrategyDcaRuleEntity> findByStrategyVersionIdInOrderBySortOrderAsc(Collection<UUID> strategyVersionIds);

    // 전략별 활성(삭제되지 않은) 최신 버전 — 규칙이 있는 버전 중 version_no 최대
    @Query(value = """
            SELECT DISTINCT ON (sv.strategy_id) sv.strategy_id AS strategyId, r.strategy_version_id AS strategyVersionId
            FROM strategy_dca_rule r
            JOIN strategy_version sv ON sv.id = r.strategy_version_id AND sv.deleted_at IS NULL
            WHERE sv.strategy_id IN (:strategyIds)
            ORDER BY sv.strategy_id, sv.version_no DESC
            """, nativeQuery = true)
    List<StrategyVersionLink> findActiveLinks(@Param("strategyIds") Collection<UUID> strategyIds);

    // 버전 규칙 교체용 즉시 삭제 — 조건은 FK ON DELETE CASCADE. 파생 delete는 flush 시 insert보다 늦어 UNIQUE와 충돌한다
    @Modifying(flushAutomatically = true)
    @Query("delete from StrategyDcaRuleEntity r where r.strategyVersionId = :strategyVersionId")
    void deleteAllByStrategyVersionId(@Param("strategyVersionId") UUID strategyVersionId);

    // native projection — strategyId ↔ 활성 versionId
    interface StrategyVersionLink {
        UUID getStrategyId();
        UUID getStrategyVersionId();
    }
}
