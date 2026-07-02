package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.CyclePositionInfiniteDetail;
import com.kista.domain.port.out.CyclePositionInfiniteDetailPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class CyclePositionInfiniteDetailPersistenceAdapter implements CyclePositionInfiniteDetailPort {

    private final CyclePositionInfiniteJpaRepository jpaRepository;

    @Override
    public Optional<CyclePositionInfiniteDetail> findByCyclePositionId(UUID cyclePositionId) {
        return jpaRepository.findById(cyclePositionId).map(this::toDomain);
    }

    @Override
    public List<CyclePositionInfiniteDetail> findLatestByCycleId(UUID cycleId, int limit) {
        return jpaRepository.findTopNByStrategyCycleIdOrderByCreatedAtDesc(cycleId, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public CyclePositionInfiniteDetail save(CyclePositionInfiniteDetail detail) {
        return toDomain(jpaRepository.save(toEntity(detail)));
    }

    @Override
    public void deleteByStrategyId(UUID strategyId) {
        jpaRepository.deleteByStrategyId(strategyId);
    }

    private CyclePositionInfiniteDetail toDomain(CyclePositionInfiniteEntity entity) {
        return new CyclePositionInfiniteDetail(entity.getCyclePositionId(), entity.isReverseMode());
    }

    private CyclePositionInfiniteEntity toEntity(CyclePositionInfiniteDetail detail) {
        CyclePositionInfiniteEntity entity = detail.cyclePositionId() != null
                ? jpaRepository.findById(detail.cyclePositionId()).orElseGet(CyclePositionInfiniteEntity::new)
                : new CyclePositionInfiniteEntity();
        entity.setCyclePositionId(detail.cyclePositionId());
        entity.setReverseMode(detail.isReverseMode());
        return entity;
    }
}
