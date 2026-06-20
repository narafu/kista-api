package com.kista.domain.model.toss;

import java.math.BigDecimal;

// Toss 종목 기본 정보 — GET /api/v1/stocks?symbol={symbol}
public record TossStockInfo(
    String symbol,          // 종목 코드 (예: SOXL)
    String name,            // 종목명
    String exchange,        // 거래소 (예: NYSE ARCA)
    String currency,        // 통화 (예: USD)
    BigDecimal lastPrice,   // 현재가
    BigDecimal prevClose    // 전일종가
) {}
