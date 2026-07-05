package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.domain.port.out.StrategyVrDetailPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

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

    private StrategyVrDetail toDomain(StrategyVrVersionEntity entity) {
        return new StrategyVrDetail(
                entity.getStrategyVersionId(),
                entity.getIntervalWeeks(),
                entity.getBandWidth(),
                entity.getRecurringAmount()
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
        return entity;
    }
}
