package com.kista.trading.application.port.output;

import com.kista.trading.domain.model.StrategyVersion;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface StrategyVersionPort {
    StrategyVersion save(StrategyVersion version);

    Optional<StrategyVersion> findById(UUID id);

    default StrategyVersion findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("전략 버전을 찾을 수 없습니다: " + id));
    }

    Optional<StrategyVersion> findActiveByStrategyId(UUID strategyId);

    // 여러 전략의 활성 버전 배치 조회 (목록 조회 N+1 방지) — strategyId 기준 versionNo 최대 1건씩
    Map<UUID, StrategyVersion> findActiveByStrategyIds(Collection<UUID> strategyIds);

    int nextVersionNo(UUID strategyId);

    void deleteByStrategyId(UUID strategyId);

    // 운영 중 재설정 시 활성 버전만 소프트 삭제 — 전략 삭제(deleteByStrategyId)와 달리 새 버전 발급 직전 호출
    void softDeleteActiveByStrategyId(UUID strategyId, Instant now);
}
