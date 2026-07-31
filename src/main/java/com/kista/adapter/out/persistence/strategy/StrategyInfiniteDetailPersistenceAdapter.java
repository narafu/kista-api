package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.StrategyInfiniteDetail;
import com.kista.domain.port.out.StrategyInfiniteDetailPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class StrategyInfiniteDetailPersistenceAdapter implements StrategyInfiniteDetailPort {

    private final StrategyInfiniteJpaRepository jpaRepository;

    @Override
    public Optional<StrategyInfiniteDetail> findByStrategyVersionId(UUID strategyVersionId) {
        return jpaRepository.findById(strategyVersionId).map(this::toDomain);
    }

    @Override
    public Optional<StrategyInfiniteDetail> findActiveByStrategyId(UUID strategyId) {
        return jpaRepository.findActiveByStrategyId(strategyId).map(this::toDomain);
    }

    @Override
    public Map<UUID, StrategyInfiniteDetail> findByStrategyVersionIds(Collection<UUID> strategyVersionIds) {
        if (strategyVersionIds.isEmpty()) return Map.of();
        return jpaRepository.findAllById(strategyVersionIds).stream()
                .map(this::toDomain)
                .collect(Collectors.toMap(StrategyInfiniteDetail::strategyVersionId, d -> d));
    }

    @Override
    public StrategyInfiniteDetail save(StrategyInfiniteDetail detail) {
        return toDomain(jpaRepository.save(toEntity(detail)));
    }

    @Override
    public void deleteByStrategyId(UUID strategyId) {
        jpaRepository.softDeleteByStrategyId(strategyId, Instant.now());
    }

    private StrategyInfiniteDetail toDomain(StrategyInfiniteEntity entity) {
        return new StrategyInfiniteDetail(entity.getStrategyVersionId(), entity.getDivisionCount());
    }

    private StrategyInfiniteEntity toEntity(StrategyInfiniteDetail detail) {
        StrategyInfiniteEntity entity = PersistenceSupport.findOrCreate(detail.strategyVersionId(), jpaRepository, StrategyInfiniteEntity::new);
        entity.setStrategyVersionId(detail.strategyVersionId());
        entity.setDivisionCount(detail.divisionCount());
        return entity;
    }
}
