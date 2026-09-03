package com.kista.trading.domain.port.out;

import com.kista.trading.domain.model.StrategyCycleVrDetail;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface StrategyCycleVrPort {

    // VR 사이클 상세 저장 (upsert — strategy_cycle_id PK 기준)
    StrategyCycleVrDetail save(StrategyCycleVrDetail detail);

    // 사이클 ID 기준 단건 조회
    Optional<StrategyCycleVrDetail> findByCycleId(UUID strategyCycleId);

    // 여러 사이클의 VR 상세 배치 조회 (목록 조회 N+1 방지) — PK(strategyCycleId) IN 조회
    Map<UUID, StrategyCycleVrDetail> findByCycleIds(Collection<UUID> cycleIds);
}
