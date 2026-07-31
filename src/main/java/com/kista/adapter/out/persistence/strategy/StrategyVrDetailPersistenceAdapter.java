package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.domain.port.out.StrategyVrDetailPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class StrategyVrDetailPersistenceAdapter implements StrategyVrDetailPort {

    private final StrategyVrVersionJpaRepository jpaRepository;

    @Override
    public StrategyVrDetail save(StrategyVrDetail detail) {
        return toDomain(jpaRepository.save(toEntity(detail)));
    }

    @Override
    public Optional<StrategyVrDetail> findByStrategyVersionId(UUID strategyVersionId) {
        return jpaRepository.findById(strategyVersionId).map(this::toDomain);
    }

    @Override
    public Optional<StrategyVrDetail> findActiveByStrategyId(UUID strategyId) {
        return jpaRepository.findActiveByStrategyId(strategyId).map(this::toDomain);
    }

    @Override
    public Map<UUID, StrategyVrDetail> findByStrategyVersionIds(Collection<UUID> strategyVersionIds) {
        if (strategyVersionIds.isEmpty()) return Map.of();
        return jpaRepository.findAllById(strategyVersionIds).stream()
                .map(this::toDomain)
                .collect(Collectors.toMap(StrategyVrDetail::strategyVersionId, d -> d));
    }

    private StrategyVrDetail toDomain(StrategyVrVersionEntity entity) {
        return new StrategyVrDetail(
                entity.getStrategyVersionId(),
                entity.getIntervalWeeks(),
                entity.getBandWidth(),
                entity.getRecurringAmount(),
                entity.getInitialGradient(),
                entity.getGGraceWeeks(),
                entity.getGStepWeeks(),
                entity.getGMax(),
                entity.getInitialPoolLimitRate(),
                entity.getPGraceWeeks(),
                entity.getPStepWeeks(),
                entity.getPoolLimitFloor()
        );
    }

    private StrategyVrVersionEntity toEntity(StrategyVrDetail detail) {
        // find-or-create upsert — strategy_version_id PK 기준
        StrategyVrVersionEntity entity = PersistenceSupport.findOrCreate(
                detail.strategyVersionId(), jpaRepository, StrategyVrVersionEntity::new);
        entity.setStrategyVersionId(detail.strategyVersionId());
        entity.setIntervalWeeks(detail.intervalWeeks());
        entity.setBandWidth(detail.bandWidth());
        entity.setRecurringAmount(detail.recurringAmount());
        entity.setInitialGradient(detail.initialGradient());
        entity.setGGraceWeeks(detail.gGraceWeeks());
        entity.setGStepWeeks(detail.gStepWeeks());
        entity.setGMax(detail.gMax());
        entity.setInitialPoolLimitRate(detail.initialPoolLimitRate());
        entity.setPGraceWeeks(detail.pGraceWeeks());
        entity.setPStepWeeks(detail.pStepWeeks());
        entity.setPoolLimitFloor(detail.poolLimitFloor());
        return entity;
    }
}
