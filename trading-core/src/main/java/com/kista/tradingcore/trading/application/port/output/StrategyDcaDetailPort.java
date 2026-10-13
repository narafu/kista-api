package com.kista.tradingcore.trading.application.port.output;

import com.kista.tradingcore.matching.domain.model.DcaConfig;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface StrategyDcaDetailPort {

    // strategy_dca_version 저장 (upsert — strategy_version_id PK 기준)
    DcaConfig save(UUID strategyVersionId, DcaConfig config);

    // 전략의 활성 버전 DCA 설정 조회 (strategy_version.deleted_at IS NULL 최신 1건)
    Optional<DcaConfig> findActiveByStrategyId(UUID strategyId);

    // 여러 전략의 활성 DCA 설정 배치 조회 — 키는 strategyId
    Map<UUID, DcaConfig> findActiveByStrategyIds(Collection<UUID> strategyIds);
}
