package com.kista.broker.domain.model;

// 종목별 판매 가능 수량 — KIS/Toss 공통 응답 타입, account.SellableQuantity 복제(순환 방지)
public record SellableQuantity(String symbol, int quantity) {}
