package com.kista.tradingcore.trading.application.port.output;

import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface StrategyDcaDetailPort {

    // 버전 규칙 저장 (같은 버전 재저장은 교체) — ruleKey는 호출부가 확정해 넘긴다
    DcaStrategyConfig save(UUID strategyVersionId, DcaStrategyConfig config);

    // 전략의 활성 버전 DCA 설정 조회 (strategy_version.deleted_at IS NULL 최신 1건)
    Optional<DcaStrategyConfig> findActiveByStrategyId(UUID strategyId);

    // 여러 전략의 활성 DCA 설정 배치 조회 — 키는 strategyId
    Map<UUID, DcaStrategyConfig> findActiveByStrategyIds(Collection<UUID> strategyIds);
}
