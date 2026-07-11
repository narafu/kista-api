package com.kista.domain.port.out;

import com.kista.domain.model.strategy.StrategyVersion;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface StrategyVersionPort {
    StrategyVersion save(StrategyVersion version);

    Optional<StrategyVersion> findById(UUID id);

    default StrategyVersion findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("전략 버전을 찾을 수 없습니다: " + id));
    }

    Optional<StrategyVersion> findActiveByStrategyId(UUID strategyId);

    int nextVersionNo(UUID strategyId);

    void deleteByStrategyId(UUID strategyId);
}
