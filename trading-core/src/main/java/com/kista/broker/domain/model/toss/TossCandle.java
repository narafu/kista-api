package com.kista.broker.domain.model.toss;

import com.kista.broker.domain.model.BrokerCandle;

import java.math.BigDecimal;
import java.time.LocalDate;

// Toss 캔들차트 1봉 — GET /api/v1/candles 응답 단위
public record TossCandle(
    LocalDate date,       // 기준일
    BigDecimal open,      // 시가
    BigDecimal high,      // 고가
    BigDecimal low,       // 저가
    BigDecimal close,     // 종가
    long volume           // 거래량
) {
    // 벤더 중립 캔들로 변환 — 어댑터가 broker 밖으로 노출할 때 사용
    public BrokerCandle toBrokerCandle() {
        return new BrokerCandle(date, open, high, low, close, volume);
    }
}
