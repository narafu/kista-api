package com.kista.domain.port.out;

import com.kista.domain.model.strategy.CyclePositionInfiniteDetail;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CyclePositionInfiniteDetailPort {

    Optional<CyclePositionInfiniteDetail> findByCyclePositionId(UUID cyclePositionId);

    List<CyclePositionInfiniteDetail> findLatestByCycleId(UUID cycleId, int limit);

    CyclePositionInfiniteDetail save(CyclePositionInfiniteDetail detail);

    void deleteByStrategyId(UUID strategyId);
}
