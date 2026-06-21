package com.kista.domain.model.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 크립토·CNN 공포탐욕지수 일별 스냅샷
public record FearGreedSnapshot(
        UUID id,
        LocalDate snapshotDate, // 수집 기준일 (KST)
        FearGreedRating cryptoRating,
        int cryptoValue,
        FearGreedRating cnnRating,
        BigDecimal cnnScore,    // 소수 둘째자리
        Instant createdAt
) {
    // 신규 저장용 — id·createdAt은 DB가 생성
    public static FearGreedSnapshot of(
            LocalDate snapshotDate,
            FearGreedRating cryptoRating,
            int cryptoValue,
            FearGreedRating cnnRating,
            BigDecimal cnnScore
    ) {
        return new FearGreedSnapshot(null, snapshotDate, cryptoRating, cryptoValue, cnnRating, cnnScore, null);
    }
}
