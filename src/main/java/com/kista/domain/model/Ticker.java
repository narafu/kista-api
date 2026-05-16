package com.kista.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public enum Ticker {
    TQQQ("NASD", new BigDecimal("0.15")), // NASDAQ
    SOXL("AMS",  new BigDecimal("0.20")), // NYSE ARCA
    USD("NASD",  new BigDecimal("0.20")); // NASDAQ

    private final String exchangeCode;         // KIS OVRS_EXCG_CD
    private final BigDecimal targetProfitRate; // 익절 목표 수익률
}
