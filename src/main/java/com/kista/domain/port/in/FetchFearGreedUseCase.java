package com.kista.domain.port.in;

import java.time.Instant;

public interface FetchFearGreedUseCase {
    // 지정 시각의 공포탐욕지수를 조회해 저장
    void fetchAndSave(Instant snapshotDate);
}
