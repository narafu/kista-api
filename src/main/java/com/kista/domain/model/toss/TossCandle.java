package com.kista.domain.model.toss;

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
) {}
