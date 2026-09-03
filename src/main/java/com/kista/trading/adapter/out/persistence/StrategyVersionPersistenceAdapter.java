package com.kista.trading.adapter.out.persistence;

import com.kista.trading.domain.model.StrategyVersion;
import com.kista.trading.application.port.output.StrategyVersionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

// com.kista.trading.adapter.out.persistence의 CyclePositionPersistenceAdapterTest/StrategyCycleVrPersistenceAdapterTest가
// @DataJpaTest 픽스처로 직접 @Import/@Autowired하고, com.kista.strategyconfig.adapter.out.persistence의
// StrategyPersistenceAdapterTest도 크로스모듈로 동일하게 픽스처 삼으므로 public 유지
@Component
@RequiredArgsConstructor
public class StrategyVersionPersistenceAdapter implements StrategyVersionPort {

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
    public Map<UUID, StrategyVersion> findActiveByStrategyIds(Collection<UUID> strategyIds) {
        if (strategyIds.isEmpty()) return Map.of();
        return jpaRepository.findActiveByStrategyIdIn(strategyIds).stream()
                .map(this::toDomain)
                .collect(Collectors.toMap(StrategyVersion::strategyId, v -> v));
    }

    @Override
    public int nextVersionNo(UUID strategyId) {
        return jpaRepository.findMaxVersionNoByStrategyId(strategyId) + 1;
    }

    @Override
    public void deleteByStrategyId(UUID strategyId) {
        jpaRepository.softDeleteByStrategyId(strategyId, Instant.now());
    }

    @Override
    public void softDeleteActiveByStrategyId(UUID strategyId, Instant now) {
        jpaRepository.softDeleteActiveByStrategyId(strategyId, now);
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
