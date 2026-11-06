package com.kista.tradingcore.trading.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface CycleCapitalEventJpaRepository extends JpaRepository<CycleCapitalEventEntity, UUID> {

    List<CycleCapitalEventEntity> findByStrategyCycleIdInOrderByOccurredAtAsc(Collection<UUID> cycleIds);

    boolean existsByStrategyCycleId(UUID cycleId);
}
