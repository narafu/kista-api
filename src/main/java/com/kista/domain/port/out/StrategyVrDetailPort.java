package com.kista.domain.port.out;

import com.kista.domain.model.strategy.StrategyVrDetail;

import java.util.Optional;
import java.util.UUID;

public interface StrategyVrDetailPort {

    // strategy_vr_version 저장 (upsert — strategy_version_id PK 기준)
    StrategyVrDetail save(StrategyVrDetail detail);

    // 버전 ID 기준 단건 조회
    Optional<StrategyVrDetail> findByStrategyVersionId(UUID strategyVersionId);

    // 전략의 활성 버전 VR 상세 조회 (strategy_version.deleted_at IS NULL 최신 1건)
    Optional<StrategyVrDetail> findActiveByStrategyId(UUID strategyId);
}
