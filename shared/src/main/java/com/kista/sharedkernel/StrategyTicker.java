package com.kista.sharedkernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.Optional;

// 거래 종목 — Strategy 도메인 nested enum(Ticker)에서 sharedkernel로 이관(constraints.md "nested enum 정책 개정").
// 상수명 byte-identical 유지 필수 — StrategyEntity.ticker @Enumerated(STRING) DB 컬럼과 직결.
@Getter
@RequiredArgsConstructor
public enum StrategyTicker {
    MAGX(new BigDecimal("0.15"), "ROUNDHILL DAILY MAGNIFICENT SEVEN 2X"), // 베타: 2.2~2.4
    USD(new BigDecimal("0.20"), "PROSHARES SEMICONDUCTORS 2X"), // 베타: 3.5~3.7
    TQQQ(new BigDecimal("0.15"), "PROSHARES QQQ 3X"), // 베타: 3.4~3.5
    SOXL(new BigDecimal("0.20"), "DIREXION SEMICONDUCTOR DAILY 3X"); // 베타: 5.3~5.5

    private final BigDecimal targetProfitRate;  // 익절 목표 수익률 (매매 도메인 정책)
    private final String description;           // 종목 설명 (UI 메타)

    // KIS 응답 String → StrategyTicker 변환. 미등록 종목이면 empty 반환 (필터링 용도)
    public static Optional<StrategyTicker> tryParse(String name) {
        if (name == null) return Optional.empty();
        try {
            return Optional.of(valueOf(name.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
