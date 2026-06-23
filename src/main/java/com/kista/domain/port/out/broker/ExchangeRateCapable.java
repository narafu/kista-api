package com.kista.domain.port.out.broker;

import com.kista.domain.model.toss.TossExchangeRate;

// 환율 조회 (Toss 전용) — 공통 API, Account 토큰 불필요
public interface ExchangeRateCapable {
    TossExchangeRate getExchangeRate();
}
