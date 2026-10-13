package com.kista.tradingcore.trading.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface StrategyDcaConditionJpaRepository extends JpaRepository<StrategyDcaConditionEntity, UUID> {

    // 여러 버전 조건 일괄 조회 — 입력 순서
    List<StrategyDcaConditionEntity> findByStrategyVersionIdInOrderBySortOrderAsc(Collection<UUID> strategyVersionIds);

    // 버전 조건 교체용 즉시 삭제 — 파생 delete는 flush 시 insert보다 늦게 실행돼 (version, sort_order) UNIQUE와 충돌한다
    @Modifying(flushAutomatically = true)
    @Query("delete from StrategyDcaConditionEntity c where c.strategyVersionId = :strategyVersionId")
    void deleteAllByStrategyVersionId(@Param("strategyVersionId") UUID strategyVersionId);
}
