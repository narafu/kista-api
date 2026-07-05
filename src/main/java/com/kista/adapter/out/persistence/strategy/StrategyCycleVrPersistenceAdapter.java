package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.StrategyCycleVrDetail;
import com.kista.domain.port.out.StrategyCycleVrPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class StrategyCycleVrPersistenceAdapter implements StrategyCycleVrPort {

    private final StrategyCycleVrJpaRepository jpaRepository;

    @Override
    public StrategyCycleVrDetail save(StrategyCycleVrDetail detail) {
        return toDomain(jpaRepository.save(toEntity(detail)));
    }

    @Override
    public Optional<StrategyCycleVrDetail> findByCycleId(UUID strategyCycleId) {
        return jpaRepository.findById(strategyCycleId).map(this::toDomain);
    }

    private StrategyCycleVrDetail toDomain(StrategyCycleVrEntity entity) {
        return new StrategyCycleVrDetail(
                entity.getStrategyCycleId(),
                entity.getValue(),
                entity.getGradient(),
                entity.getPoolLimit()
        );
    }

    private StrategyCycleVrEntity toEntity(StrategyCycleVrDetail detail) {
        // find-or-create upsert — strategy_cycle_id PK 기준
        StrategyCycleVrEntity entity = PersistenceSupport.findOrCreate(
                detail.strategyCycleId(), jpaRepository, StrategyCycleVrEntity::new);
        entity.setStrategyCycleId(detail.strategyCycleId());
        entity.setValue(detail.value());
        entity.setGradient(detail.gradient());
        entity.setPoolLimit(detail.poolLimit());
        return entity;
    }
}
