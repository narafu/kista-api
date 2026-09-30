package com.kista.broker.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// 벤더 중립 캔들차트 1봉 — 증권사별 캔들 응답(TossCandle 등)을 어댑터가 이 타입으로 변환해 노출한다
public record BrokerCandle(
    LocalDate date,       // 기준일
    BigDecimal open,      // 시가
    BigDecimal high,      // 고가
    BigDecimal low,       // 저가
    BigDecimal close,     // 종가
    long volume           // 거래량
) {}
