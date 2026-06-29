package com.kista.domain.model.strategy;

import java.time.Instant;
import java.util.UUID;

// 전략 설정 버전 이력 — 같은 Strategy 아래 활성 버전 1개 유지
public record StrategyVersion(
        UUID id,               // PK
        UUID strategyId,       // FK → strategy.id
        int versionNo,         // 전략 내 버전 번호
        Instant createdAt,     // 생성 시각
        Instant deletedAt      // soft-delete (null=활성)
) {}
