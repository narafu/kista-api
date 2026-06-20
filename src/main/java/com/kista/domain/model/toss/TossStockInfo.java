package com.kista.domain.model.toss;

// Toss 종목 기본 정보 — GET /api/v1/stocks?symbols={symbol}
// 주의: Toss stocks API는 가격 정보 미제공 — 현재가는 GET /api/v1/prices 별도 조회
public record TossStockInfo(
    String symbol,        // 종목 코드 (예: SOXL)
    String name,          // 한글 종목명
    String englishName,   // 영문 종목명
    String market,        // 거래소/시장 (예: NYSE ARCA)
    String currency,      // 통화 (예: USD)
    String status         // 종목 상태 (예: NORMAL)
) {}
