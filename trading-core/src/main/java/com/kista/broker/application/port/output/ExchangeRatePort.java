package com.kista.broker.application.port.output;

import com.kista.broker.domain.model.ExchangeRateQuote;

// 환율 조회 (현재 Toss만 구현) — 공통 API, Account 토큰 불필요. 벤더 중립 ExchangeRateQuote 반환
public interface ExchangeRatePort {
    ExchangeRateQuote getExchangeRate();
}
