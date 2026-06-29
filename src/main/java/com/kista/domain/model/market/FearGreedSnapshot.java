package com.kista.domain.model.market;

import java.time.Instant;
import java.util.UUID;

// 크립토·CNN 공포탐욕지수 시점별 스냅샷
public record FearGreedSnapshot(
        UUID id,
        String source,         // "CRYPTO" | "CNN"
        Instant snapshotDate,  // 수집 시각
        int value,             // 0-100
        FearGreedRating rating,
        Instant createdAt
) {
    // 신규 저장용 — id·createdAt은 DB가 생성
    public static FearGreedSnapshot of(
            String source,
            Instant snapshotDate,
            int value,
            FearGreedRating rating
    ) {
        return new FearGreedSnapshot(null, source, snapshotDate, value, rating, null);
    }
}
