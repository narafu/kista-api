package com.kista.domain.port.out;

import com.kista.domain.model.toss.TossExchangeRate;

// 공통 API — 개별 계좌 토큰 불필요, 관리자 자격증명으로 조회
public interface TossExchangeRatePort {
    // GET /api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW
    TossExchangeRate getExchangeRate();
}
