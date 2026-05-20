package com.kista.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum Ticker {
    TQQQ("NASD", new BigDecimal("0.15")), // NASDAQ
    SOXL("AMS",  new BigDecimal("0.20")), // NYSE ARCA
    USD("NASD",  new BigDecimal("0.20")); // NASDAQ

    private final String exchangeCode;         // KIS OVRS_EXCG_CD
    private final BigDecimal targetProfitRate; // 익절 목표 수익률

    // KIS 응답 String → Ticker 변환. 미등록 종목이면 empty 반환 (필터링 용도)
    public static Optional<Ticker> tryParse(String name) {
        if (name == null) return Optional.empty();
        try { return Optional.of(valueOf(name.trim())); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }
}
