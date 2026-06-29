package com.kista.domain.port.out;

import com.kista.domain.model.strategy.StrategyInfiniteDetail;

import java.util.Optional;
import java.util.UUID;

public interface StrategyInfiniteDetailPort {

    Optional<StrategyInfiniteDetail> findByStrategyVersionId(UUID strategyVersionId);

    Optional<StrategyInfiniteDetail> findActiveByStrategyId(UUID strategyId);

    StrategyInfiniteDetail save(StrategyInfiniteDetail detail);

    void deleteByStrategyId(UUID strategyId);
}
