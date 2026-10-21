package com.kista.tradingcore.trading.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface StrategyDcaConditionJpaRepository extends JpaRepository<StrategyDcaConditionEntity, UUID> {

    // 여러 규칙 조건 일괄 조회 — 입력 순서
    List<StrategyDcaConditionEntity> findByRuleIdInOrderBySortOrderAsc(Collection<UUID> ruleIds);
}
