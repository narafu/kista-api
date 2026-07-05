package com.kista.domain.port.out;

import com.kista.domain.model.strategy.StrategyCycleVrDetail;

import java.util.Optional;
import java.util.UUID;

public interface StrategyCycleVrPort {

    // VR 사이클 상세 저장 (upsert — strategy_cycle_id PK 기준)
    StrategyCycleVrDetail save(StrategyCycleVrDetail detail);

    // 사이클 ID 기준 단건 조회
    Optional<StrategyCycleVrDetail> findByCycleId(UUID strategyCycleId);
}
