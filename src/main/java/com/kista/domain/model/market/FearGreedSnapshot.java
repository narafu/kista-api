package com.kista.domain.model.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 크립토·CNN 공포탐욕지수 일별 스냅샷 (source별 1행)
public record FearGreedSnapshot(
        UUID id,
        String source,          // "CRYPTO" | "CNN"
        LocalDate snapshotDate, // 수집 기준일 (KST)
        int value,              // 0-100
        FearGreedRating rating,
        Instant createdAt
) {
    // 신규 저장용 — id·createdAt은 DB가 생성
    public static FearGreedSnapshot of(
            String source,
            LocalDate snapshotDate,
            int value,
            FearGreedRating rating
    ) {
        return new FearGreedSnapshot(null, source, snapshotDate, value, rating, null);
    }
}
