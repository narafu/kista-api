package com.kista.trading.adapter.out.persistence;

import com.kista.trading.domain.model.StrategyInfiniteDetail;
import com.kista.trading.application.port.output.StrategyInfiniteDetailPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

// 레거시 com.kista.adapter.out.persistence.strategy의 StrategyPersistenceAdapterTest가
// @DataJpaTest 픽스처로 직접 @Import/@Autowired하므로 public 유지 (모듈 경계상 레거시는 OPEN이라 안전)
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class StrategyInfiniteDetailPersistenceAdapter implements StrategyInfiniteDetailPort {

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
