package com.kista.contract.broker;

import java.math.BigDecimal;
import java.time.LocalDate;

// 일봉 1건 — GET /api/internal/broker/candles/latest 응답
public record DailyCandleResponse(
        LocalDate date,     // 기준일
        BigDecimal open,    // 시가
        BigDecimal high,    // 고가
        BigDecimal low,     // 저가
        BigDecimal close,   // 종가
        long volume         // 거래량
) {}
