package com.kista.api.market.application.usecase;

import java.time.Instant;

public interface FetchFearGreedUseCase {
    // 지정 시각의 CNN 공포탐욕지수를 조회해 저장
    void fetchAndSave(Instant snapshotDate);
}
