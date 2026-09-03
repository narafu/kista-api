package com.kista.broker.domain.port.out;

import com.kista.broker.domain.model.toss.TossExchangeRate;

// 환율 조회 (Toss 전용) — 공통 API, Account 토큰 불필요
public interface ExchangeRatePort {
    TossExchangeRate getExchangeRate();
}
