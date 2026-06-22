package com.kista.domain.model.account;

// 종목별 판매 가능 수량 — KIS/Toss 공통 응답 타입
public record SellableQuantity(
    String symbol,   // 종목 코드 (예: SOXL)
    int    quantity  // 판매 가능 수량
) {}
