package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.trading.application.port.output.CycleCapitalEventPort;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class CycleCapitalEventPersistenceAdapter implements CycleCapitalEventPort {

    private final CycleCapitalEventJpaRepository jpaRepository;

    @Override
    public CycleCapitalEvent save(CycleCapitalEvent event) {
        return toDomain(jpaRepository.save(toEntity(event)));
    }

    @Override
    public List<CycleCapitalEvent> findByCycleIds(Collection<UUID> cycleIds) {
        if (cycleIds.isEmpty()) return List.of();
        return jpaRepository.findByStrategyCycleIdInOrderByOccurredAtAsc(cycleIds).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public boolean existsByCycleId(UUID cycleId) {
        return jpaRepository.existsByStrategyCycleId(cycleId);
    }

    private CycleCapitalEventEntity toEntity(CycleCapitalEvent e) {
        CycleCapitalEventEntity entity = new CycleCapitalEventEntity();
        entity.setId(e.id());
        entity.setStrategyCycleId(e.strategyCycleId());
        entity.setCyclePositionId(e.cyclePositionId());
        entity.setKind(e.kind());
        entity.setDepositDelta(e.depositDelta());
        entity.setHoldingsDelta(e.holdingsDelta());
        entity.setPrincipalDelta(e.principalDelta());
        entity.setOccurredAt(e.occurredAt());
        return entity;
    }

    private CycleCapitalEvent toDomain(CycleCapitalEventEntity e) {
        return new CycleCapitalEvent(e.getId(), e.getStrategyCycleId(), e.getCyclePositionId(), e.getKind(),
                e.getDepositDelta(), e.getHoldingsDelta(), e.getPrincipalDelta(), e.getOccurredAt());
    }
}
