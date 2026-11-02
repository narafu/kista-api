package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.trading.domain.model.CyclePositionInfiniteDetail;
import com.kista.tradingcore.trading.application.port.output.CyclePositionInfiniteDetailPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class CyclePositionInfiniteDetailPersistenceAdapter implements CyclePositionInfiniteDetailPort {

    private final CyclePositionInfiniteJpaRepository jpaRepository;

    @Override
    public List<CyclePositionInfiniteDetail> findLatestByCycleId(UUID cycleId, int limit) {
        return jpaRepository.findByStrategyCycleIdOrderByCreatedAtDesc(cycleId, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Map<UUID, CyclePositionInfiniteDetail> findLatestByCycleIds(Collection<UUID> cycleIds) {
        if (cycleIds.isEmpty()) return Map.of();
        return jpaRepository.findLatestByStrategyCycleIdIn(cycleIds).stream()
                .collect(Collectors.toMap(CyclePositionInfiniteJpaRepository.LatestRow::getCycleId,
                        r -> new CyclePositionInfiniteDetail(r.getCyclePositionId(), r.getReverseMode())));
    }

    @Override
    public CyclePositionInfiniteDetail save(CyclePositionInfiniteDetail detail) {
        return toDomain(jpaRepository.save(toEntity(detail)));
    }

    private CyclePositionInfiniteDetail toDomain(CyclePositionInfiniteEntity entity) {
        return new CyclePositionInfiniteDetail(entity.getCyclePositionId(), entity.isReverseMode());
    }

    private CyclePositionInfiniteEntity toEntity(CyclePositionInfiniteDetail detail) {
        CyclePositionInfiniteEntity entity = PersistenceSupport.findOrCreate(detail.cyclePositionId(), jpaRepository, CyclePositionInfiniteEntity::new);
        entity.setCyclePositionId(detail.cyclePositionId());
        entity.setReverseMode(detail.isReverseMode());
        return entity;
    }
}
