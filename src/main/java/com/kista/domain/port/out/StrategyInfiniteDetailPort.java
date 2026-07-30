package com.kista.domain.port.out;

import com.kista.domain.model.strategy.StrategyInfiniteDetail;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface StrategyInfiniteDetailPort {

    Optional<StrategyInfiniteDetail> findByStrategyVersionId(UUID strategyVersionId);

    Optional<StrategyInfiniteDetail> findActiveByStrategyId(UUID strategyId);

    // 여러 버전의 INFINITE 상세 배치 조회 (목록 조회 N+1 방지) — PK(strategyVersionId) IN 조회
    Map<UUID, StrategyInfiniteDetail> findByStrategyVersionIds(Collection<UUID> strategyVersionIds);

    StrategyInfiniteDetail save(StrategyInfiniteDetail detail);

    void deleteByStrategyId(UUID strategyId);
}
