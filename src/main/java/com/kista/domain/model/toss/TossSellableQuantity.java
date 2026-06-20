package com.kista.domain.model.toss;

// Toss 판매 가능 수량 — GET /api/v1/sellable-quantity?symbol={symbol}
public record TossSellableQuantity(
    String symbol,   // 종목 코드 (예: SOXL)
    int    quantity  // 판매 가능 수량
) {}
