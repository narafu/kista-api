package com.kista.api.market.application.port.output;

import com.kista.api.market.domain.model.CryptoFearGreedHistory;

// alternative.me Crypto Fear & Greed Index API
public interface CryptoFearGreedPort {
    // 최신 limit일 일별 이력(시각 오름차순, snapshotDate는 지수 기준일 UTC 00:00) + 다음 갱신까지 남은 시간
    CryptoFearGreedHistory fetchHistory(int limit);
}
