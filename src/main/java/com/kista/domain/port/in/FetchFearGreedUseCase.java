package com.kista.domain.port.in;

import java.time.LocalDate;

public interface FetchFearGreedUseCase {
    // 지정 날짜의 공포탐욕지수를 조회해 저장 (이미 저장된 날짜면 skip)
    void fetchAndSave(LocalDate date);
}
