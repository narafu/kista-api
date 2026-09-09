package com.kista.matching.domain.model;

// 주문 유형 — 증권사 실행 지시
public enum OrderType {
    LOC,   // Limit On Close: 종가 지정가 주문
    MOC,   // Market On Close: 종가 시장가 주문
    LIMIT  // 일반 지정가 주문
}
