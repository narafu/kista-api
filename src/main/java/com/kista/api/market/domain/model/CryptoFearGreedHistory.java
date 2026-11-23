package com.kista.api.market.domain.model;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;

// alternative.me 이력 조회 결과
public record CryptoFearGreedHistory(
        List<FearGreedSnapshot> snapshots,  // 시각 오름차순
        @Nullable Duration untilNextUpdate  // alternative.me time_until_update — 없거나 해석 불가면 null
) {}
