package com.kista.trading.application.port.output;

import com.kista.trading.domain.model.CyclePositionInfiniteDetail;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CyclePositionInfiniteDetailPort {

    Optional<CyclePositionInfiniteDetail> findByCyclePositionId(UUID cyclePositionId);

    List<CyclePositionInfiniteDetail> findLatestByCycleId(UUID cycleId, int limit);

    // 여러 포지션의 INFINITE 상세 배치 조회 (목록 조회 N+1 방지) — PK(cyclePositionId) IN 조회
    Map<UUID, CyclePositionInfiniteDetail> findByCyclePositionIds(Collection<UUID> cyclePositionIds);

    CyclePositionInfiniteDetail save(CyclePositionInfiniteDetail detail);

    void deleteByStrategyId(UUID strategyId);
}
