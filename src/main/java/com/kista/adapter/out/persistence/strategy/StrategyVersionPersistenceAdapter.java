package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.StrategyVersion;
import com.kista.domain.port.out.StrategyVersionPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class StrategyVersionPersistenceAdapter implements StrategyVersionPort {

    private final StrategyVersionJpaRepository jpaRepository;

    @Override
    public StrategyVersion save(StrategyVersion version) {
        return toDomain(jpaRepository.save(toEntity(version)));
    }

    @Override
    public Optional<StrategyVersion> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<StrategyVersion> findActiveByStrategyId(UUID strategyId) {
        return jpaRepository.findTop1ByStrategyIdAndDeletedAtIsNullOrderByVersionNoDesc(strategyId)
                .map(this::toDomain);
    }

    @Override
    public int nextVersionNo(UUID strategyId) {
        return jpaRepository.findMaxVersionNoByStrategyId(strategyId) + 1;
    }

    @Override
    public void deleteByStrategyId(UUID strategyId) {
        jpaRepository.softDeleteByStrategyId(strategyId, Instant.now());
    }

    private StrategyVersion toDomain(StrategyVersionEntity entity) {
        return new StrategyVersion(
                entity.getId(),
                entity.getStrategyId(),
                entity.getVersionNo(),
                entity.getCreatedAt(),
                entity.getDeletedAt()
        );
    }

    private StrategyVersionEntity toEntity(StrategyVersion version) {
        StrategyVersionEntity entity = PersistenceSupport.findOrCreate(version.id(), jpaRepository, StrategyVersionEntity::new);
        entity.setId(version.id());
        entity.setStrategyId(version.strategyId());
        entity.setVersionNo(version.versionNo());
        entity.setDeletedAt(version.deletedAt());
        return entity;
    }
}
