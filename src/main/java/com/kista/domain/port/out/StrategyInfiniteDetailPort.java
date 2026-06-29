package com.kista.domain.port.out;

import com.kista.domain.model.strategy.StrategyInfiniteDetail;

import java.util.Optional;
import java.util.UUID;

public interface StrategyInfiniteDetailPort {

    Optional<StrategyInfiniteDetail> findByStrategyId(UUID strategyId);

    StrategyInfiniteDetail save(StrategyInfiniteDetail detail);

    void deleteByStrategyId(UUID strategyId);
}
